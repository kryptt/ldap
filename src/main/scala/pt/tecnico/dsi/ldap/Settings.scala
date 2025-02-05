package pt.tecnico.dsi.ldap

import java.security.{KeyStore, SecureRandom}
import java.time.Duration
import javax.net.ssl.SSLContext
import com.typesafe.config.{Config, ConfigFactory}
import com.unboundid.util.ssl.TrustStoreTrustManager
import org.ldaptive._
import org.ldaptive.pool._
import org.ldaptive.ssl.{KeyStoreCredentialConfig, SslConfig}
import scala.jdk.CollectionConverters._

class Settings(config: Config = ConfigFactory.load()) {
  // This verifies that the Config is sane and has our reference config. Importantly, we specify the "path"
  // path so we only validate settings that belong to this library.
  private val ldapConfig = {
    val reference = ConfigFactory.defaultReference()
    val finalConfig = config.withFallback(reference)
    finalConfig.checkValid(reference, "ldap")
    finalConfig.getConfig("ldap")
  }

  private val sslConfigs = ldapConfig.getConfig("ssl")
  private val poolConfigs = ldapConfig.getConfig("pool")
  private val searchConfigs = ldapConfig.getConfig("search")

  val host: String = ldapConfig.getString("host")
  val baseDomain: String = ldapConfig.getString("base-dn")
  val bindDN: String = ldapConfig.getString("bind-dn")
  val bindPassword: String = ldapConfig.getString("bind-password")

  val connectionTimeout: Duration = ldapConfig.getDuration("connection-timeout")
  val responseTimeout: Duration = ldapConfig.getDuration("response-timeout")

  val enablePool: Boolean = poolConfigs.getBoolean("enable-pool")
  val blockWaitTime: Duration = poolConfigs.getDuration("block-wait-time")
  val minPoolSize: Int = poolConfigs.getInt("min-pool-size")
  val maxPoolSize: Int = poolConfigs.getInt("max-pool-size")
  val validationPeriod: Duration = poolConfigs.getDuration("validation-period")
  val prunePeriod: Duration = poolConfigs.getDuration("prune-period")
  val pruneIdleTime: Duration = poolConfigs.getDuration("prune-idle-time")

  val enableSSL: Boolean = sslConfigs.getBoolean("enable-ssl")
  val trustStore: String = sslConfigs.getString("trust-store")
  val trustStorePassword: String = sslConfigs.getString("trust-store-password")
  val protocol: String = sslConfigs.getString("protocol")
  val enabledAlgorithms: Seq[String] = sslConfigs.getStringList("enabled-algorithms").asScala.toSeq
  val randomNumberGeneratorAlgorithm: String = sslConfigs.getString("random-number-generator")

  private val credential: Credential = new Credential(bindPassword)

  val connectionConfig = new ConnectionConfig(host)
  connectionConfig.setConnectTimeout(connectionTimeout)
  connectionConfig.setResponseTimeout(responseTimeout)
  connectionConfig.setUseStartTLS(false)
  connectionConfig.setConnectionInitializers(new BindConnectionInitializer(bindDN, credential))

  val keyStoreConfig = new KeyStoreCredentialConfig()
  keyStoreConfig.setTrustStore(s"file:/$trustStore")
  keyStoreConfig.setTrustStorePassword(trustStorePassword)
  keyStoreConfig.setTrustStoreType(KeyStore.getDefaultType)

  if (enableSSL) {
  val sslConfig = new SslConfig()
  sslConfig.setCredentialConfig(keyStoreConfig)
  sslConfig.setEnabledProtocols(protocol)
  sslConfig.setEnabledCipherSuites(enabledAlgorithms: _*)

    val randomNumberGenerator = {
      val rng = randomNumberGeneratorAlgorithm match {
        case s@("SHA1PRNG" | "NativePRNG") =>
          //log.debug("SSL random number generator set to: {}", s)
          // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
          // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
          SecureRandom.getInstance(s)
        case "" | "SecureRandom" =>
          //log.debug("SSL random number generator set to [SecureRandom]")
          new SecureRandom
        case _ =>
          //log.warning(LogMarker.Security, "Unknown SSL random number generator [{}] falling back to SecureRandom", unknown)
          new SecureRandom
      }
      rng.nextInt() // prevent stall on first access
      rng
    }

    sslConfig.setTrustManagers(
new TrustStoreTrustManager(trustStore, trustStorePassword.toCharArray, KeyStore.getDefaultType, true)
    )
    connectionConfig.setSslConfig(sslConfig)
  }

  val defaultConnectionFactory: DefaultConnectionFactory = new DefaultConnectionFactory(connectionConfig)

  val poolConfig = PooledConnectionFactory.builder()
  .config(connectionConfig)
  .min(minPoolSize)
  .max(maxPoolSize)
  .validateOnCheckOut(true)
  .validatePeriodically(true)

  val pooledConnectionFactory: PooledConnectionFactory = poolConfig.build()

  val searchDereferenceAlias: String = searchConfigs.getString("dereference-alias")
  val searchScope: String = searchConfigs.getString("scope")
  val searchSizeLimit: Int = searchConfigs.getInt("size-limit")
  val searchTimeLimit: Duration = searchConfigs.getDuration("time-limit")
}
