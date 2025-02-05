package pt.tecnico.dsi.ldap

import com.typesafe.scalalogging.LazyLogging
import org.ldaptive.pool._
import org.ldaptive._

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class Ldap(val settings: Settings = new Settings()) extends LazyLogging {

  private val connectionFactory: ConnectionFactory = if (settings.enablePool) {
    settings.pooledConnectionFactory.initialize()
    settings.pooledConnectionFactory
  } else {
    settings.defaultConnectionFactory
  }

  def closePool(): Unit = {
    logger.info("Closing connection pool")
    connectionFactory.close()
  }

  private def logAvailableConnectionsInPool: String = connectionFactory match {
    case cf: PooledConnectionFactory => s"${cf.availableCount()} connections available"
    case _ => "" //Nothing to do
  }

  private def closeConnection(connection: Connection): Unit = {
    connection.close()
    logger.debug(s"Connection closed. $logAvailableConnectionsInPool")
  }

  //  private def addOperationHandler[R <: Request, S](operation: AbstractOperation[R,S]): Unit = {
  //    val handler = new operation.ReopenOperationExceptionHandler()
  //    handler.setRetry(5)
  //
  //    operation.setOperationExceptionHandler(handler)
  //  }

  private def fixLdapEntry(entry: LdapEntry): Option[Entry] = {
    Option(entry).filter {
      e =>
        Some(e.getAttributes).isDefined
    }.map(fixLdapAttribute)
  }

  private def fixLdapAttribute(e: LdapEntry): Entry = {
    val (binaryAttributes, textAttributes) = e.getAttributes.asScala.partition(_.isBinary)
    val dn = Option(e.getDn)

    val mappedTextAttributes: Map[String, List[String]] = textAttributes.map { attribute =>
      attribute.getName -> attribute.getStringValues.asScala.toList
    }.toMap

    val mappedBinaryAttributes: Map[String, List[Array[Byte]]] = binaryAttributes.map { attribute =>
      attribute.getName -> attribute.getBinaryValues.asScala.toList
    }.toMap

    Entry(dn, mappedTextAttributes, mappedBinaryAttributes)
  }

  def appendBaseDn(dn: String = ""): String = if (dn.nonEmpty) {
    s"$dn,${settings.baseDomain}"
  } else {
    settings.baseDomain
  }

  private def toLdapAttributes(textAttributes: Map[String, List[String]],
                               binaryAttributes: Map[String, List[Array[Byte]]]): Seq[LdapAttribute] = {
    val result = textAttributes.map {
      case (name, values) => new LdapAttribute(name, values: _*)
    } ++ binaryAttributes.map {
      case (name, values) => new LdapAttribute(name, values: _*)
    }

    result.toSeq
  }


  /**
    * Add a new entry to Ldap, using a connection. If an equal entry already exists, nothing is done. Otherwise, the
    * entry is updated with the new values.
    *
    * @param dn               the entry identifier. `base-dn` is appended in the end
    * @param textAttributes   the entry's text attributes
    * @param binaryAttributes the entry's binary attributes
    * @param ex               the execution context where the `Future` will be executed
    * @return a `Future` wrapping the add operation
    */
  def addEntry(dn: String = "", textAttributes: Map[String, List[String]] = Map.empty,
               binaryAttributes: Map[String, List[Array[Byte]]] = Map.empty)(implicit ex: ExecutionContext): Future[Unit] = {
    val ldapAttributes = toLdapAttributes(textAttributes, binaryAttributes)
    val operation = new AddOperation(connectionFactory)
      Future {
        logger.info(s"Adding ${appendBaseDn(dn)}")
        operation.execute(new AddRequest(appendBaseDn(dn), ldapAttributes.asJavaCollection))
      }.flatMap { r =>
        r.getResultCode match {
          case ResultCode.ENTRY_ALREADY_EXISTS => replaceAttributes(dn, textAttributes, binaryAttributes)
          case ResultCode.SUCCESS => Future.unit
          case rc => Future.failed(LdapException(r))
        }
      }
  }

  /**
    * Deletes an entry from Ldap.
    *
    * @param dn the entry identifier. `base-dn` is appendend in the end
    * @param ex the execution context where the `Future` will be executed
    * @return   a `Future` wrapping the delete operation
    */
  def deleteEntry(dn: String = "")(implicit ex: ExecutionContext): Future[Unit] = {
    logger.info(s"Deleting entry ${appendBaseDn(dn)}")
    val operation = new DeleteOperation(connectionFactory)
    Future {
      operation.execute(new DeleteRequest(appendBaseDn(dn)))
      ()
    }.recoverWith {
      case ldapException: LdapException if ldapException.getResultCode == ResultCode.NO_SUCH_OBJECT => Future.unit
    }
  }

  def addAttributes(dn: String = "", textAttributes: Map[String, List[String]] = Map.empty,
                    binaryAttributes: Map[String, List[Array[Byte]]] = Map.empty)(implicit ex: ExecutionContext): Future[Unit] = {
    val attributes = toLdapAttributes(textAttributes, binaryAttributes)

    val attributesModification: Seq[AttributeModification] = attributes.map { attribute =>
      new AttributeModification(AttributeModification.Type.ADD, attribute)
    }

    executeModifyOperation(dn, attributesModification)(s"Adding attributes for ${appendBaseDn(dn)}")
  }

  def replaceAttributes(dn: String = "", textAttributes: Map[String, List[String]],
                        binaryAttributes: Map[String, List[Array[Byte]]])(implicit ex: ExecutionContext): Future[Unit] = {
    val attributes = toLdapAttributes(textAttributes, binaryAttributes)
    val attributesModification: Seq[AttributeModification] = attributes.map { attribute =>
      new AttributeModification(AttributeModification.Type.REPLACE, attribute)
    }

    executeModifyOperation(dn, attributesModification)(s"Replacing attributes for ${appendBaseDn(dn)}")
  }

  def removeAttributes(dn: String = "", attributes: Seq[String])(implicit ex: ExecutionContext): Future[Unit] = {
    val attributesModification: Seq[AttributeModification] = attributes.map { attribute =>
      new AttributeModification(AttributeModification.Type.DELETE, new LdapAttribute(attribute))
    }

    executeModifyOperation(dn, attributesModification)(s"Removing ${attributes.mkString(", ")} attributes for ${appendBaseDn(dn)}")
  }

  private def executeModifyOperation(dn: String, attributes: Seq[AttributeModification])
                                    (logMessage: String)
                                    (implicit ex: ExecutionContext): Future[Unit] = {
    logger.info(logMessage)
    val operation = new ModifyOperation(connectionFactory)
    Future(operation.execute(new ModifyRequest(appendBaseDn(dn), attributes: _*)))
  }

  private def createSearchResult(dn: String, filter: String, attributes: Seq[String], size: Int)
                                (implicit ex: ExecutionContext): Future[SearchResponse] = {
    val request = new SearchRequest(appendBaseDn(dn), filter, attributes: _*)
    request.setDerefAliases(DerefAliases.valueOf(settings.searchDereferenceAlias))
    request.setSearchScope(SearchScope.valueOf(settings.searchScope))
    request.setSizeLimit(size)
    request.setTimeLimit(settings.searchTimeLimit)

    val operation: SearchOperation = new SearchOperation(connectionFactory)
    Future(operation.execute(request))
  }

  def search(dn: String = "", filter: String, returningAttributes: Seq[String] = Seq.empty, size: Int = settings.searchSizeLimit)
            (implicit ex: ExecutionContext): Future[Seq[Entry]] = {
    assert(size > -1, "The number of results expected should be a non-negative integer.")
    assert(filter.nonEmpty, "Filter cannot be empty.")
      logger.info(s"Performing a search($size) for ${appendBaseDn(dn)}, with $filter and returning ${returningAttributes.mkString(", ")} attributes")

      createSearchResult(dn, filter, returningAttributes, size).map { result =>
        if (size == 1) {
          fixLdapEntry(result.getEntry).toSeq
        } else {
          result.getEntries.asScala.flatMap(fixLdapEntry).toSeq
        }
      }.recoverWith {
        case ldapException: LdapException if ldapException.getResultCode == ResultCode.NO_SUCH_OBJECT =>
          Future(Seq.empty[Entry])
      }
  }

  def searchAll(dn: String = "", filter: String, returningAttributes: Seq[String] = Seq.empty)
               (implicit ex: ExecutionContext): Future[Seq[Entry]] = {
    search(dn, filter, returningAttributes, 0)
  }

}
