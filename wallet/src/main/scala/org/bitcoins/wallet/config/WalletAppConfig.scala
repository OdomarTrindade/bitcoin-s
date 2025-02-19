package org.bitcoins.wallet.config

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.Config
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.config.{AppConfigFactoryBase, ConfigOps}
import org.bitcoins.core.api.CallbackConfig
import org.bitcoins.core.api.chain.ChainQueryApi
import org.bitcoins.core.api.feeprovider.FeeRateApi
import org.bitcoins.core.api.node.NodeApi
import org.bitcoins.core.hd._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.wallet.keymanagement._
import org.bitcoins.crypto.AesPassword
import org.bitcoins.db.DatabaseDriver.{PostgreSQL, SQLite}
import org.bitcoins.db._
import org.bitcoins.db.models.MasterXPubDAO
import org.bitcoins.db.util.{DBMasterXPubApi, MasterXPubUtil}
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.tor.config.TorAppConfig
import org.bitcoins.wallet.callback.{
  WalletCallbackStreamManager,
  WalletCallbacks
}
import org.bitcoins.wallet.config.WalletAppConfig.RebroadcastTransactionsRunnable
import org.bitcoins.wallet.db.WalletDbManagement
import org.bitcoins.wallet.models.AccountDAO
import org.bitcoins.wallet.{Wallet, WalletLogger}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent._
import scala.concurrent.duration.{
  Duration,
  DurationInt,
  DurationLong,
  FiniteDuration
}
import scala.concurrent.{Await, ExecutionContext, Future}

/** Configuration for the Bitcoin-S wallet
  * @param directory
  *   The data directory of the wallet
  * @param conf
  *   Optional sequence of configuration overrides
  */
case class WalletAppConfig(
    baseDatadir: Path,
    configOverrides: Vector[Config],
    kmConfOpt: Option[KeyManagerAppConfig] = None
)(implicit val system: ActorSystem)
    extends DbAppConfig
    with WalletDbManagement
    with JdbcProfileComponent[WalletAppConfig]
    with DBMasterXPubApi
    with CallbackConfig[WalletCallbacks] {

  implicit override val ec: ExecutionContext = system.dispatcher

  override protected[bitcoins] def moduleName: String =
    WalletAppConfig.moduleName

  override protected[bitcoins] type ConfigType = WalletAppConfig

  override protected[bitcoins] def newConfigOfType(
      configs: Vector[Config]
  ): WalletAppConfig =
    WalletAppConfig(baseDatadir, configs)

  override def appConfig: WalletAppConfig = this

  lazy val torConf: TorAppConfig =
    TorAppConfig(baseDatadir, Some(moduleName), configOverrides)

  private[wallet] lazy val scheduler: ScheduledExecutorService = {
    Executors.newScheduledThreadPool(
      1,
      AsyncUtil.getNewThreadFactory(
        s"bitcoin-s-wallet-scheduler-${System.currentTimeMillis()}"
      )
    )
  }

  private lazy val rescanThreadFactory: ThreadFactory =
    AsyncUtil.getNewThreadFactory("bitcoin-s-rescan")

  /** Threads for rescanning the wallet */
  private[wallet] lazy val rescanThreadPool: ExecutorService =
    Executors.newFixedThreadPool(
      Runtime.getRuntime.availableProcessors(),
      rescanThreadFactory
    )

  override lazy val callbackFactory: WalletCallbacks.type = WalletCallbacks

  lazy val kmConf: KeyManagerAppConfig =
    kmConfOpt.getOrElse(KeyManagerAppConfig(baseDatadir, configOverrides))

  lazy val defaultAccountKind: HDPurpose =
    config.getString("bitcoin-s.wallet.defaultAccountType") match {
      case "legacy"        => HDPurpose.Legacy
      case "segwit"        => HDPurpose.SegWit
      case "nested-segwit" => HDPurpose.NestedSegWit
      // todo: validate this pre-app startup
      case other: String =>
        throw new RuntimeException(s"$other is not a valid account type!")
    }

  lazy val defaultAddressType: AddressType = {
    defaultAccountKind match {
      case HDPurpose.Legacy       => AddressType.Legacy
      case HDPurpose.NestedSegWit => AddressType.NestedSegWit
      case HDPurpose.SegWit       => AddressType.SegWit
      // todo: validate this pre-app startup
      case other =>
        throw new RuntimeException(s"$other is not a valid account type!")
    }
  }

  lazy val defaultAccount: HDAccount = {
    val purpose = defaultAccountKind
    HDAccount(
      coin = HDCoin(purpose, HDCoinType.fromNetwork(network)),
      index = 0
    )
  }

  lazy val bloomFalsePositiveRate: Double =
    config.getDouble("bitcoin-s.wallet.bloomFalsePositiveRate")

  lazy val addressGapLimit: Int =
    config.getInt("bitcoin-s.wallet.addressGapLimit")

  lazy val discoveryBatchSize: Int =
    config.getInt("bitcoin-s.wallet.discoveryBatchSize")

  lazy val requiredConfirmations: Int = {
    val confs = config.getInt("bitcoin-s.wallet.requiredConfirmations")
    require(
      confs >= 1,
      s"requiredConfirmations cannot be less than 1, got: $confs"
    )
    confs
  }

  lazy val longTermFeeRate: SatoshisPerVirtualByte = {
    val feeRate = config.getInt("bitcoin-s.wallet.longTermFeeRate")
    require(
      feeRate >= 0,
      s"longTermFeeRate cannot be less than 0, got: $feeRate"
    )
    SatoshisPerVirtualByte.fromLong(feeRate)
  }

  lazy val rebroadcastFrequency: Duration = {
    if (config.hasPath("bitcoin-s.wallet.rebroadcastFrequency")) {
      val javaDuration =
        config.getDuration("bitcoin-s.wallet.rebroadcastFrequency")
      new FiniteDuration(javaDuration.toNanos, TimeUnit.NANOSECONDS)
    } else {
      4.hours
    }
  }

  lazy val feeProviderNameOpt: Option[String] = {
    config.getStringOrNone("bitcoin-s.fee-provider.name")
  }

  lazy val feeProviderTargetOpt: Option[Int] =
    config.getIntOpt("bitcoin-s.fee-provider.target")

  lazy val feeRatePollInterval: FiniteDuration = config
    .getDuration("bitcoin-s.fee-provider.poll-interval")
    .getSeconds
    .seconds

  lazy val feeRatePollDelay: FiniteDuration =
    config.getDuration("bitcoin-s.fee-provider.poll-delay").getSeconds.seconds

  lazy val allowExternalDLCAddresses: Boolean =
    config.getBoolean("bitcoin-s.wallet.allowExternalDLCAddresses")

  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt

  lazy val walletName: String = kmConf.walletName

  override lazy val dbPath: Path = {
    val pathStrOpt =
      config.getStringOrNone(s"bitcoin-s.$moduleName.db.path")
    pathStrOpt match {
      case Some(pathStr) =>
        Paths.get(pathStr).resolve(walletName)
      case None =>
        sys.error(s"Could not find dbPath for $moduleName.db.path")
    }
  }

  override lazy val schemaName: Option[String] = {
    driver match {
      case PostgreSQL =>
        val schema = PostgresUtil.getSchemaName(
          moduleName = moduleName,
          walletName = walletName
        )
        Some(schema)
      case SQLite =>
        None
    }
  }
  private val masterXPubDAO: MasterXPubDAO = MasterXPubDAO()(ec, this)

  override def start(): Future[Unit] = {
    if (Files.notExists(datadir)) {
      Files.createDirectories(datadir)
    }

    for {
      _ <- super.start()
      _ <- kmConf.start()
      masterXpub = kmConf.toBip39KeyManager.getRootXPub
      numMigrations = migrate()
      isExists <- seedExists()
      _ <- {
        logger.info(
          s"Starting wallet with xpub=${masterXpub} walletName=${walletName}"
        )
        if (!isExists) {
          masterXPubDAO
            .create(masterXpub)
            .map(_ => ())
        } else {
          MasterXPubUtil.checkMasterXPub(masterXpub, masterXPubDAO)
        }
      }
    } yield {
      logger.debug(s"Initializing wallet setup")
      if (isHikariLoggingEnabled) {
        // .get is safe because hikari logging is enabled
        startHikariLogger(hikariLoggingInterval.get)
      }
      logger.info(s"Applied $numMigrations to the wallet project")
    }
  }

  override def stop(): Future[Unit] = {
    val stopCallbacksF = callBacks match {
      case stream: WalletCallbackStreamManager => stream.stop()
      case _: WalletCallbacks =>
        Future.unit
    }
    if (isHikariLoggingEnabled) {
      stopHikariLogger()
    }

    stopCallbacksF.flatMap { _ =>
      clearCallbacks()
      stopRebroadcastTxsScheduler()
      // this eagerly shuts down all scheduled tasks on the scheduler
      // in the future, we should actually cancel all things that are scheduled
      // manually, and then shutdown the scheduler
      scheduler.shutdownNow()
      rescanThreadPool.shutdownNow()
      super.stop()
    }

  }

  /** The path to our encrypted mnemonic seed */
  override lazy val seedPath: Path = kmConf.seedPath

  def kmParams: KeyManagerParams =
    KeyManagerParams(kmConf.seedPath, defaultAccountKind, network)

  /** How much elements we can have in
    * [[org.bitcoins.wallet.internal.AddressHandling.addressRequestQueue]]
    * before we throw an exception
    */
  def addressQueueSize: Int = {
    if (config.hasPath("bitcoin-s.wallet.addressQueueSize")) {
      config.getInt("bitcoin-s.wallet.addressQueueSize")
    } else {
      100
    }
  }

  /** How long we wait while generating an address in
    * [[org.bitcoins.wallet.internal.AddressHandling.addressRequestQueue]]
    * before we timeout
    */
  def addressQueueTimeout: Duration = {
    if (config.hasPath("bitcoin-s.wallet.addressQueueTimeout")) {
      val javaDuration =
        config.getDuration("bitcoin-s.wallet.addressQueueTimeout")
      new FiniteDuration(javaDuration.toNanos, TimeUnit.NANOSECONDS)
    } else {
      5.second
    }
  }

  /** Checks if the following exist
    *   1. A seed exists 2. wallet exists 3. The account exists
    */
  def hasWallet()(implicit
      walletConf: WalletAppConfig,
      ec: ExecutionContext
  ): Future[Boolean] = {
    if (kmConf.seedExists()) {
      val hdCoin = walletConf.defaultAccount.coin
      val walletDB = walletConf.dbPath resolve walletConf.dbName
      walletConf.driver match {
        case PostgreSQL =>
          AccountDAO().read((hdCoin, 0)).map(_.isDefined)
        case SQLite =>
          if (Files.exists(walletDB))
            AccountDAO().read((hdCoin, 0)).map(_.isDefined)
          else Future.successful(false)
      }
    } else {
      Future.successful(false)
    }
  }

  /** Creates a wallet based on this [[WalletAppConfig]] */
  def createHDWallet(
      nodeApi: NodeApi,
      chainQueryApi: ChainQueryApi,
      feeRateApi: FeeRateApi
  )(implicit system: ActorSystem): Future[Wallet] = {
    WalletAppConfig.createHDWallet(
      nodeApi = nodeApi,
      chainQueryApi = chainQueryApi,
      feeRateApi = feeRateApi
    )(this, system)
  }

  private[this] var rebroadcastTransactionsCancelOpt
      : Option[ScheduledFuture[_]] = None

  /** Starts the wallet's rebroadcast transaction scheduler */
  def startRebroadcastTxsScheduler(wallet: Wallet): Unit = synchronized {
    rebroadcastTransactionsCancelOpt match {
      case Some(_) =>
        // already scheduled, do nothing
        ()
      case None =>
        logger.info(s"Starting wallet rebroadcast task")

        val interval = rebroadcastFrequency.toSeconds
        val initDelay = interval
        val future =
          scheduler.scheduleAtFixedRate(
            RebroadcastTransactionsRunnable(wallet),
            initDelay,
            interval,
            TimeUnit.SECONDS
          )
        rebroadcastTransactionsCancelOpt = Some(future)
        ()
    }
  }

  /** Kills the wallet's rebroadcast transaction scheduler */
  def stopRebroadcastTxsScheduler(): Unit = synchronized {
    rebroadcastTransactionsCancelOpt match {
      case Some(cancel) =>
        if (!cancel.isCancelled) {
          logger.info(s"Stopping wallet rebroadcast task")
          cancel.cancel(true)
        } else {
          rebroadcastTransactionsCancelOpt = None
        }
        ()
      case None => ()
    }
  }

  /** The creation time of the mnemonic seed If we cannot decrypt the seed
    * because of invalid passwords, we return None
    */
  def creationTime: Instant = {
    kmConf.creationTime
  }
}

object WalletAppConfig
    extends AppConfigFactoryBase[WalletAppConfig, ActorSystem]
    with WalletLogger {

  final val DEFAULT_WALLET_NAME: String =
    KeyManagerAppConfig.DEFAULT_WALLET_NAME

  val moduleName: String = "wallet"

  /** Constructs a wallet configuration from the default Bitcoin-S data
    * directory and given list of configuration overrides.
    */
  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      system: ActorSystem
  ): WalletAppConfig =
    WalletAppConfig(datadir, confs)

  /** Creates a wallet based on the given [[WalletAppConfig]] */
  def createHDWallet(
      nodeApi: NodeApi,
      chainQueryApi: ChainQueryApi,
      feeRateApi: FeeRateApi
  )(implicit
      walletConf: WalletAppConfig,
      system: ActorSystem
  ): Future[Wallet] = {
    import system.dispatcher
    walletConf.hasWallet().flatMap { walletExists =>
      val bip39PasswordOpt = walletConf.bip39PasswordOpt

      if (walletExists) {
        logger.info(s"Using pre-existing wallet")
        val wallet =
          Wallet(nodeApi, chainQueryApi, feeRateApi)
        Future.successful(wallet)
      } else {
        logger.info(s"Creating new wallet")
        val unInitializedWallet =
          Wallet(nodeApi, chainQueryApi, feeRateApi)

        Wallet.initialize(
          wallet = unInitializedWallet,
          bip39PasswordOpt = bip39PasswordOpt
        )
      }
    }
  }

  case class RebroadcastTransactionsRunnable(wallet: Wallet)(implicit
      ec: ExecutionContext
  ) extends Runnable {

    override def run(): Unit = {
      val f = for {
        txs <- wallet.getTransactionsToBroadcast

        _ = {
          if (txs.size > 1)
            logger.info(
              s"Rebroadcasting ${txs.size} transactions, txids=${txs.map(_.txIdBE)}"
            )
        }

        _ <- wallet.nodeApi.broadcastTransactions(txs)
      } yield ()

      // Make sure broadcasting completes
      // Wrap in try in case of spurious failure
      try {
        Await.result(f, 60.seconds)
      } catch {
        case scala.util.control.NonFatal(exn) =>
          logger.error(s"Failed to broadcast txs", exn)
      }
      ()
    }
  }
}
