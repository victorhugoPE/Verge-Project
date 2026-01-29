package net.sf.l2j.gameserver

import kotlinx.coroutines.*
import net.sf.l2j.Config
import net.sf.l2j.commons.lang.StringUtil
import net.sf.l2j.commons.logging.CLogger
import net.sf.l2j.commons.mmocore.SelectorConfig
import net.sf.l2j.commons.mmocore.SelectorThread
import net.sf.l2j.commons.network.IPv4Filter
import net.sf.l2j.commons.pool.ConnectionPool
import net.sf.l2j.commons.pool.ThreadPool
import net.sf.l2j.commons.util.SysUtil
import net.sf.l2j.gameserver.communitybbs.CommunityBoard
import net.sf.l2j.gameserver.data.SkillTable
import net.sf.l2j.gameserver.data.cache.CrestCache
import net.sf.l2j.gameserver.data.cache.HtmCache
import net.sf.l2j.gameserver.data.manager.*
import net.sf.l2j.gameserver.data.sql.BookmarkTable
import net.sf.l2j.gameserver.data.sql.ClanTable
import net.sf.l2j.gameserver.data.sql.PlayerInfoTable
import net.sf.l2j.gameserver.data.sql.ServerMemoTable
import net.sf.l2j.gameserver.data.xml.*
import net.sf.l2j.gameserver.geoengine.GeoEngine
import net.sf.l2j.gameserver.handler.*
import net.sf.l2j.gameserver.idfactory.IdFactory
import net.sf.l2j.gameserver.model.World
import net.sf.l2j.gameserver.model.memo.GlobalMemo
import net.sf.l2j.gameserver.model.olympiad.Olympiad
import net.sf.l2j.gameserver.model.olympiad.OlympiadGameManager
import net.sf.l2j.gameserver.network.GameClient
import net.sf.l2j.gameserver.network.GamePacketHandler
import net.sf.l2j.gameserver.taskmanager.*
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.util.logging.LogManager
import kotlin.system.exitProcess

/**
 * GameServer em Kotlin (Verge Edition).
 * Arquitetura: High Performance Async Loading + Shutdown Blindado.
 */
class GameServer {
    
    private val _selectorThread: SelectorThread<GameClient>
    private val _isServerCrash: Boolean

    companion object {
        private val LOGGER = CLogger(GameServer::class.java.name)
        
        // Instância interna para o Kotlin
        private lateinit var _instance: GameServer

        @JvmStatic
        fun main(args: Array<String>) {
            _instance = GameServer()
        }

        // Bridge Estática para o Java antigo (Shutdown.java) encontrar a instância
        @JvmStatic
        fun getInstance(): GameServer = _instance
    }

    init {
        val startTime = System.currentTimeMillis()

        // 1. Preparação de Diretórios e Logs
        prepareSystem()

        // 2. Carregamento de Configs e Pools
        StringUtil.printSection("Config & Pools")
        Config.loadGameServer()
        ConnectionPool.init()
        ThreadPool.init()
        IdFactory.getInstance()

        // 3. Carregamento do Mundo (Base)
        StringUtil.printSection("World Base")
        World.getInstance()
        AnnouncementData.getInstance()
        ServerMemoTable.getInstance()
        GlobalMemo.getInstance() 
        _isServerCrash = ServerMemoTable.getInstance().getBool("server_crash", false)

        // ========================================================================
        // TURBO LOADING: Carregamento Paralelo Massivo
        // ========================================================================
        StringUtil.printSection("Async Parallel Loading")
        LOGGER.info("Starting High-Performance loading sequence...")
        
        runBlocking {
            val jobs = listOf(
                // BLOCO 1: DADOS PESADOS (XML/SQL)
                async(Dispatchers.IO) { 
                    LOGGER.info("Loading Skills...")
                    SkillTable.getInstance()
                    SkillTreeData.getInstance()
                },
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Items & Equipment...")
                    ItemData.getInstance()
                    ArmorSetData.getInstance()
                    RecipeData.getInstance()
                    SummonItemData.getInstance()
                    HennaData.getInstance()
                },
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Multisell & BuyLists...")
                    BuyListManager.getInstance()
                    MultisellData.getInstance()
                },
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Static Data (Fish, Spellbooks, Augment, Crystals)...")
                    FishData.getInstance()
                    SpellbookData.getInstance()
                    SoulCrystalData.getInstance()
                    AugmentationData.getInstance()
                    CursedWeaponManager.getInstance()
                },
                
                // BLOCO 2: GEOENGINE E MAPA
                async(Dispatchers.IO) {
                    LOGGER.info("Loading GeoEngine & Zones...")
                    GeoEngine.getInstance()
                    ZoneManager.getInstance() 
                },

                // BLOCO 3: CLÃS E COMUNIDADE
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Clans & Community...")
                    ClanTable.getInstance()
                    CrestCache.getInstance()
                    HtmCache.getInstance()
                    CommunityBoard.getInstance()
                    BookmarkTable.getInstance()
                    PetitionManager.getInstance()
                },

                // BLOCO 4: PERSONAGENS E TEMPLATES
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Character Templates...")
                    PlayerData.getInstance()
                    PlayerInfoTable.getInstance()
                    PlayerLevelData.getInstance()
                    AdminData.getInstance()
                    NewbieBuffData.getInstance()
                    HealSpsData.getInstance()
                    RestartPointData.getInstance()
                },

                // BLOCO 5: SISTEMAS DE JOGO (Castelos carregam aqui)
                async(Dispatchers.IO) {
                    LOGGER.info("Loading Game Systems (7 Signs, Manor, Castles)...")
                    SevenSignsManager.getInstance()
                    FestivalOfDarknessManager.getInstance()
                    ManorAreaData.getInstance()
                    CastleManorManager.getInstance()
                    CastleManager.getInstance()
                    ClanHallDecoData.getInstance()
                    ClanHallManager.getInstance()
                    PartyMatchRoomManager.getInstance()
                    RaidPointManager.getInstance()
                }
            )
            
            jobs.awaitAll()
            LOGGER.info(">>> CORE SYSTEMS LOADED IN PARALLEL.")
        }
        
        // ========================================================================
        // FASE SEQUENCIAL: Ordem corrigida para evitar conflitos de I/O
        // ========================================================================
        
        // Spawn das Portas (Precisa dos Castelos carregados no Bloco 5)
        StringUtil.printSection("Doors & Spawns")
        DoorData.getInstance().spawn()

        // Task Managers
        StringUtil.printSection("Task Managers")
        AiTaskManager.getInstance()
        AttackStanceTaskManager.getInstance()
        BoatTaskManager.getInstance()
        DecayTaskManager.getInstance()
        GameTimeTaskManager.getInstance()
        ItemsOnGroundTaskManager.getInstance()
        PvpFlagTaskManager.getInstance()
        ShadowItemTaskManager.getInstance()
        WaterTaskManager.getInstance()
        InventoryUpdateTaskManager.getInstance()
        ItemInstanceTaskManager.getInstance()

        // NPCs e Conteúdo
        StringUtil.printSection("Content")
        BufferManager.getInstance()
        NpcData.getInstance()
        WalkerRouteData.getInstance()
        StaticObjectData.getInstance()
        InstantTeleportData.getInstance()
        TeleportData.getInstance()
        ObserverGroupData.getInstance()

        CastleManager.getInstance().spawnEntities()

        // Olympiad & Hero
        OlympiadGameManager.getInstance()
        Olympiad.getInstance()
        HeroManager.getInstance()

        // ========================================================================
        // CORREÇÃO: Handlers carregados ANTES dos Scripts
        // Isso evita o erro "zip file closed" porque Scripts podem fechar o JAR.
        // ========================================================================
        StringUtil.printSection("Handlers")
        LOGGER.info("Loaded ${AdminCommandHandler.getInstance().size()} admin command handlers.")
        LOGGER.info("Loaded ${ChatHandler.getInstance().size()} chat handlers.")
        LOGGER.info("Loaded ${ItemHandler.getInstance().size()} item handlers.")
        LOGGER.info("Loaded ${SkillHandler.getInstance().size()} skill handlers.")
        LOGGER.info("Loaded ${TargetHandler.getInstance().size()} target handlers.")
        LOGGER.info("Loaded ${UserCommandHandler.getInstance().size()} user command handlers.")

        // Scripts (Carrega por último para não travar o I/O dos handlers)
        ScriptData.getInstance()

        if (Config.ALLOW_BOAT) BoatData.getInstance().load()

        // Events
        DerbyTrackManager.getInstance()
        LotteryManager.getInstance()
        CoupleManager.getInstance()
        if (Config.ALLOW_FISH_CHAMPIONSHIP) FishingChampionshipManager.getInstance()

        // Spawn Mobs Final
        LOGGER.info("Spawning World NPCs...")
        SpawnManager.getInstance().spawn()
        
        // ========================================================================
        // SISTEMA DE SHUTDOWN BLINDADO
        // ========================================================================
        StringUtil.printSection("System")
        
        Runtime.getRuntime().addShutdownHook(Thread({
            println("\n\n")
            println("=================================================")
            println("      FINALIZANDO SERVIDOR (SOLICITACAO DO USUARIO)      ")
            println("=================================================")
            println("(!) NAO FECHE ESTA JANELA AINDA (!)")
            println("(!) Salvando dados... (Isso pode levar alguns segundos)")

            try {
                // Chama o Shutdown Singleton manualmente
                // Certifique-se que o Shutdown.java permite a thread "L2J-Shutdown-Manager"
                Shutdown.getInstance().run()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            println("=================================================")
            println("      DADOS SALVOS COM SUCESSO.                  ")
            println("      Encerrando processo em 3 segundos...       ")
            println("=================================================")

            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
        }, "L2J-Shutdown-Manager"))

        if (_isServerCrash)
            LOGGER.info("Server crashed on last session!")
        else
            ServerMemoTable.getInstance().set("server_crash", true)

        val loadTime = (System.currentTimeMillis() - startTime) / 1000.0
        LOGGER.info("Gameserver started in ${loadTime}s. Memory: ${SysUtil.getUsedMemory()} / ${SysUtil.getMaxMemory()} Mo.")
        LOGGER.info("Maximum allowed players: ${Config.MAXIMUM_ONLINE_USERS}.")

        // Login Connection
        StringUtil.printSection("Login")
        LoginServerThread.getInstance().start()

        // Network Selector Setup
        val sc = SelectorConfig()
        sc.MAX_READ_PER_PASS = Config.MMO_MAX_READ_PER_PASS
        sc.MAX_SEND_PER_PASS = Config.MMO_MAX_SEND_PER_PASS
        sc.SLEEP_TIME = Config.MMO_SELECTOR_SLEEP_TIME
        sc.HELPER_BUFFER_COUNT = Config.MMO_HELPER_BUFFER_COUNT

        val handler = GamePacketHandler()
        _selectorThread = SelectorThread(sc, handler, handler, handler, IPv4Filter())

        var bindAddress: InetAddress? = null
        if (Config.GAMESERVER_HOSTNAME != "*") {
            try {
                bindAddress = InetAddress.getByName(Config.GAMESERVER_HOSTNAME)
            } catch (e: Exception) {
                LOGGER.error("The GameServer bind address is invalid, using all available IPs.", e)
            }
        }

        try {
            _selectorThread.openServerSocket(bindAddress, Config.GAMESERVER_PORT)
        } catch (e: Exception) {
            LOGGER.error("Failed to open server socket.", e)
            exitProcess(1)
        }
        _selectorThread.start()
    }
    
    fun getSelectorThread(): SelectorThread<GameClient> = _selectorThread
    fun isServerCrash(): Boolean = _isServerCrash

    private fun prepareSystem() {
        File("./log").mkdir()
        File("./log/chat").mkdir()
        File("./log/console").mkdir()
        File("./log/error").mkdir()
        File("./log/gmaudit").mkdir()
        File("./log/item").mkdir()
        File("./data/crests").mkdirs()

        try {
            FileInputStream(File("config/logging.properties")).use { `is` ->
                LogManager.getLogManager().readConfiguration(`is`)
            }
        } catch (e: Exception) {
            println("WARNING: Could not load logging.properties")
        }
    }
}