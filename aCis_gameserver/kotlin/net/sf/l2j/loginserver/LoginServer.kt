package net.sf.l2j.loginserver

import net.sf.l2j.Config
import net.sf.l2j.commons.lang.StringUtil
import net.sf.l2j.commons.logging.CLogger
import net.sf.l2j.commons.mmocore.SelectorConfig
import net.sf.l2j.commons.mmocore.SelectorThread
import net.sf.l2j.commons.pool.ConnectionPool
import net.sf.l2j.loginserver.data.manager.GameServerManager
import net.sf.l2j.loginserver.data.manager.IpBanManager
import net.sf.l2j.loginserver.data.sql.AccountTable
import net.sf.l2j.loginserver.network.LoginClient
import net.sf.l2j.loginserver.network.LoginPacketHandler
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.util.logging.LogManager
import kotlin.system.exitProcess

class LoginServer {

    // CORREÇÃO: lateinit var permite inicializar depois (dentro do init)
    lateinit var gameServerListener: GameServerListener
        private set
        
    private lateinit var selectorThread: SelectorThread<LoginClient>

    companion object {
        private val LOGGER = CLogger(LoginServer::class.java.name)
        const val PROTOCOL_REV = 0x0102

        private lateinit var _instance: LoginServer

        @JvmStatic
        fun main(args: Array<String>) {
            _instance = LoginServer()
        }

        @JvmStatic
        fun getInstance(): LoginServer = _instance
    }

    init {
        File("./log/console").mkdirs()
        File("./log/error").mkdirs()

        try {
            FileInputStream(File("config/logging.properties")).use { 
                LogManager.getLogManager().readConfiguration(it) 
            }
        } catch (e: Exception) {
            println("WARNING: Could not load logging.properties")
        }

        StringUtil.printSection("Config")
        Config.loadLoginServer()

        StringUtil.printSection("Poolers")
        ConnectionPool.init()

        AccountTable.getInstance()

        StringUtil.printSection("LoginController")
        LoginController.getInstance()

        StringUtil.printSection("GameServerManager")
        GameServerManager.getInstance()

        StringUtil.printSection("Ban List")
        IpBanManager.getInstance()

        StringUtil.printSection("IP, Ports & Socket infos")
        
        var bindAddress: InetAddress? = null
        if (Config.LOGINSERVER_HOSTNAME != "*") {
            try {
                bindAddress = InetAddress.getByName(Config.LOGINSERVER_HOSTNAME)
            } catch (e: Exception) {
                LOGGER.error("The LoginServer bind address is invalid, using all available IPs.", e)
            }
        }

        val sc = SelectorConfig().apply {
            MAX_READ_PER_PASS = Config.MMO_MAX_READ_PER_PASS
            MAX_SEND_PER_PASS = Config.MMO_MAX_SEND_PER_PASS
            SLEEP_TIME = Config.MMO_SELECTOR_SLEEP_TIME
            HELPER_BUFFER_COUNT = Config.MMO_HELPER_BUFFER_COUNT
        }

        val lph = LoginPacketHandler()
        val sh = SelectorHelper()

        try {
            // CORREÇÃO: Removido o underscore (_)
            selectorThread = SelectorThread(sc, sh, lph, sh, sh)
        } catch (e: Exception) {
            LOGGER.error("Failed to open selector.", e)
            exitProcess(1)
        }

        try {
            // CORREÇÃO: Inicialização correta da propriedade lateinit
            gameServerListener = GameServerListener()
            gameServerListener.start()
            LOGGER.info("Listening for gameservers on ${Config.GAMESERVER_LOGIN_HOSTNAME}:${Config.GAMESERVER_LOGIN_PORT}.")
        } catch (e: Exception) {
            LOGGER.error("Failed to start the gameserver listener.", e)
            exitProcess(1)
        }

        try {
            selectorThread.openServerSocket(bindAddress, Config.LOGINSERVER_PORT)
        } catch (e: Exception) {
            LOGGER.error("Failed to open server socket.", e)
            exitProcess(1)
        }

        selectorThread.start()
        
        val ipDisplay = bindAddress?.hostAddress ?: "*"
        LOGGER.info("Loginserver ready on $ipDisplay:${Config.LOGINSERVER_PORT}.")

        StringUtil.printSection("Waiting for gameserver answer")
    }

    fun shutdown(restart: Boolean) {
        exitProcess(if (restart) 2 else 0)
    }
}