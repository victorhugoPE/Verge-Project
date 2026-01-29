package ext.mods.commons.pool

import net.sf.l2j.Config
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * Pool de threads otimizado para Java 25.
 * VERSÃO "UNLEASHED" (CORRIGIDA): Removemos os limites conservadores para evitar starvation (fila cheia).
 * L2J precisa de muitas threads porque faz muitas operações bloqueantes (DB/Locks).
 */
object CoroutinePool {
    
    private val LOGGER: Logger = Logger.getLogger(CoroutinePool::class.java.name)

    private var scheduledExecutors: Array<ScheduledThreadPoolExecutor>? = null
    private var instantExecutors: Array<ThreadPoolExecutor>? = null
    // Pool exclusivo para Rede (IO)
    private var ioExecutors: Array<ThreadPoolExecutor>? = null
    
    private var pathfindingExecutor: ThreadPoolExecutor? = null
    
    // CORREÇÃO: Variável que faltava
    private var virtualExecutor: ExecutorService? = null
    
    private var forkJoinPool: ForkJoinPool? = null
    
    private val BackgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    val PathfindingDispatcher: CoroutineDispatcher by lazy {
        pathfindingExecutor?.asCoroutineDispatcher() ?: Dispatchers.Default
    }
    
    // Contadores
    private val totalTasksSubmitted = LongAdder()
    private val totalTasksCompleted = LongAdder()
    private val totalTasksRejected = LongAdder()
    private val totalExecutionTimeMs = AtomicLong(0)
    
    private const val MAX_DELAY_MS: Long = 2_000_000_000L 

    @JvmStatic 
    fun init() {
        try {
            if (scheduledExecutors != null) return

            val cpuCores = Runtime.getRuntime().availableProcessors()
            
            // Vamos usar pools menores em quantidade, mas com capacidade MAIOR de threads
            val poolCount = (cpuCores / 2).coerceAtLeast(2)

            // --- Scheduled Pools (Timers) ---
            scheduledExecutors = Array(poolCount) { index ->
                val threadProvider = ThreadProvider("Scheduled-Pool-$index", Thread.NORM_PRIORITY, true)
                ScheduledThreadPoolExecutor(
                    2, // Core maior para aguentar mais timers simultâneos
                    threadProvider as ThreadFactory
                ).apply {
                    removeOnCancelPolicy = true
                    executeExistingDelayedTasksAfterShutdownPolicy = false
                }
            }
            
            // --- Instant Pools (Game Logic) ---
            // Aumentamos drasticamente o maxPoolSize (Burst).
            // Se o DB travar, o pool cria mais threads em vez de travar o jogo.
            instantExecutors = Array(poolCount) { index ->
                val threadProvider = ThreadProvider("Game-Pool-$index", Thread.NORM_PRIORITY, true)
                ThreadPoolExecutor(
                    2,  // Core: Mantém 2 threads vivas sempre
                    64, // Max: Pode subir até 64 threads SE PRECISAR (Evita travadas)
                    30L, TimeUnit.SECONDS,
                    LinkedBlockingQueue(10000), // Fila gigante
                    threadProvider as ThreadFactory,
                    RejectedExecutionHandlerOptimized()
                ).apply {
                    allowCoreThreadTimeOut(true) // Permite matar threads extras ociosas
                }
            }

            // --- IO Pools (Rede Exclusiva) ---
            // Separa a rede da lógica. Se o jogo travar calculando drop, o ping continua.
            ioExecutors = Array(poolCount) { index ->
                val threadProvider = ThreadProvider("IO-Pool-$index", Thread.MAX_PRIORITY, true) // Prioridade Máxima
                ThreadPoolExecutor(
                    1, 
                    32, // Burst alto para aguentar flood de pacotes
                    60L, TimeUnit.SECONDS,
                    LinkedBlockingQueue(10000),
                    threadProvider as ThreadFactory,
                    ThreadPoolExecutor.CallerRunsPolicy()
                )
            }
            
            // --- Virtual Threads (Java 25) ---
            virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
            
            // --- Pathfinding Pool ---
            // Mantemos limitado para não comer 100% da CPU
            val pathfindingCount = 4
            val pathfindingThreadProvider = ThreadProvider("Pathfinding-Thread", Thread.NORM_PRIORITY - 1, true)
            pathfindingExecutor = ThreadPoolExecutor(
                pathfindingCount,
                pathfindingCount,
                60L, TimeUnit.SECONDS,
                LinkedBlockingQueue(500),
                pathfindingThreadProvider as ThreadFactory,
                ThreadPoolExecutor.DiscardOldestPolicy() // Se lotar, ignora movimentos velhos em vez de travar
            ).apply {
                allowCoreThreadTimeOut(false)
                prestartAllCoreThreads()
            }
            
            // --- ForkJoin Pool ---
            forkJoinPool = ForkJoinPool(cpuCores, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)
            
            LOGGER.info("=== CoroutinePool UNLEASHED (High Performance) ===")
            LOGGER.info("  - Cores: $cpuCores")
            LOGGER.info("  - Game Pools: $poolCount x 64 Threads (Max)")
            LOGGER.info("  - IO Pools: $poolCount x 32 Threads (Isolated)")
            
        } catch (e: Exception) {
            LOGGER.severe("ERRO CRÍTICO ao inicializar CoroutinePool: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Executa Lógica de Jogo (GamePacketHandler).
     * Usa o pool "Instant" que agora aguenta até 64 threads por núcleo.
     */
/**
     * VERSÃO FULL COROUTINE:
     * Transforma qualquer Runnable legado em uma Coroutine lançada no Pool de Jogo.
     */
	@JvmStatic 
	    fun execute(r: Runnable) {
	        val executors = instantExecutors
	        if (executors == null || executors.isEmpty()) {
	            try { r.run() } catch (e: Exception) { e.printStackTrace() }
	            return
	        }
	
	        // Escolhe um pool para balancear
	        val selectedPool = executors[ThreadLocalRandom.current().nextInt(executors.size)]
	        
	        // A MÁGICA ACONTECE AQUI:
	        // Convertemos o ThreadPool do Java em um Dispatcher do Kotlin.
	        val dispatcher = selectedPool.asCoroutineDispatcher()
	        
	        // Lançamos uma Coroutine "fire-and-forget"
	        BackgroundScope.launch(dispatcher) {
	            try {
	                r.run()
	            } catch (e: Exception) {
	                LOGGER.severe("Erro em tarefa Coroutine: ${e.message}")
	                e.printStackTrace()
	            }
	        }
	    }
    
    /**
     * Executa Rede/Pacotes.
     * AGORA USA UM POOL ISOLADO (ioExecutors).
     * Isso impede que o lag do banco de dados afete o envio de pacotes.
     */
    @JvmStatic
    fun executeIO(task: Runnable) {
        val executors = ioExecutors ?: return execute(task)
        try {
            val selectedPool = executors[ThreadLocalRandom.current().nextInt(executors.size)]
            selectedPool.execute(task)
        } catch (e: Exception) {
            execute(task) // Fallback
        }
    }
    
    @JvmStatic
    fun schedule(r: Runnable, delay: Long): ScheduledFuture<*> {
        val executors = scheduledExecutors ?: throw IllegalStateException("Pool nulo")
        return try {
            totalTasksSubmitted.increment()
            val selectedPool = executors[ThreadLocalRandom.current().nextInt(executors.size)]
            selectedPool.schedule(RunnableWrapper(r), validate(delay), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            ScheduledFutureTask.cancelled()
        }
    }
    
    @JvmStatic
    fun scheduleAtFixedRate(r: Runnable, delay: Long, period: Long): ScheduledFuture<*> {
        val executors = scheduledExecutors ?: throw IllegalStateException("Pool nulo")
        return try {
            totalTasksSubmitted.increment()
            val selectedPool = executors[ThreadLocalRandom.current().nextInt(executors.size)]
            selectedPool.scheduleAtFixedRate(RunnableWrapper(r), validate(delay), validate(period), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            ScheduledFutureTask.cancelled()
        }
    }

    @JvmStatic
    fun executePathfinding(task: Runnable) {
        pathfindingExecutor?.execute(RunnableWrapper(task)) ?: task.run()
    }
    
    @JvmStatic
    fun executeParallel(task: Runnable): Job {
        return BackgroundScope.launch(Dispatchers.Default) {
            try { task.run() } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    @JvmStatic
    fun executeParallelBlocking(task: Runnable) {
        forkJoinPool?.submit(task)?.join() ?: task.run()
    }

    // --- Métricas ---
    @JvmStatic fun getPathfindingQueueSize(): Int = pathfindingExecutor?.queue?.size ?: 0
    @JvmStatic fun getPathfindingActiveCount(): Int = pathfindingExecutor?.activeCount ?: 0
    @JvmStatic fun getTotalTasksSubmitted(): Long = totalTasksSubmitted.sum()
    @JvmStatic fun getTotalTasksCompleted(): Long = totalTasksCompleted.sum()
    @JvmStatic fun getTotalTasksRejected(): Long = totalTasksRejected.sum()
    
    @JvmStatic
    fun getAverageExecutionTimeMs(): Double {
        val completed = totalTasksCompleted.sum()
        return if (completed > 0) totalExecutionTimeMs.get().toDouble() / completed else 0.0
    }
    
    @JvmStatic
    fun getMetrics(): Map<String, Any> {
        val instantPools = instantExecutors ?: emptyArray()
        val scheduledPools = scheduledExecutors ?: emptyArray()
        
        return mapOf(
            "tasksSubmitted" to totalTasksSubmitted.sum(),
            "tasksCompleted" to totalTasksCompleted.sum(),
            "averageLatency" to getAverageExecutionTimeMs(),
            "scheduledPools" to scheduledPools.size,
            "scheduledQueueSize" to scheduledPools.sumOf { it.queue.size },
            "instantPools" to instantPools.size,
            "instantPoolsActive" to instantPools.sumOf { it.activeCount },
            "instantPoolsQueueSize" to instantPools.sumOf { it.queue.size },
            "pathfindingActive" to getPathfindingActiveCount(),
            "pathfindingQueueSize" to getPathfindingQueueSize()
        )
    }

    private fun validate(delay: Long): Long = max(0, min(MAX_DELAY_MS, delay))

    @JvmStatic
    fun shutdown() {
        BackgroundScope.cancel()
        scheduledExecutors?.forEach { it.shutdown() }
        instantExecutors?.forEach { it.shutdown() }
        ioExecutors?.forEach { it.shutdown() }
        pathfindingExecutor?.shutdown()
        virtualExecutor?.shutdown()
        forkJoinPool?.shutdown()
    }
}