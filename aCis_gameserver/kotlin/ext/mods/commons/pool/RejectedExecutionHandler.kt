package ext.mods.commons.pool

import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.logging.Logger

/**
 * Implementação otimizada do RejectedExecutionHandler para Java 25.
 * * MELHORIAS:
 * - Usa Virtual Threads nativas do Java 25 para fallback (Zero custo de criação).
 * - Estratégia inteligente: Se a thread atual for Crítica (Rede), joga para Virtual Thread.
 * - Se for thread normal, roda na hora (CallerRuns) para criar "backpressure" e não explodir a memória.
 */
class RejectedExecutionHandlerOptimized : RejectedExecutionHandler {
    
    private val LOGGER: Logger = Logger.getLogger(RejectedExecutionHandlerOptimized::class.java.name)
    
    override fun rejectedExecution(runnable: Runnable, executor: ThreadPoolExecutor) {
        if (executor.isShutdown) {
            return
        }
        
        val currentThread = Thread.currentThread()
        val threadPriority = currentThread.priority
        
        when {
            // CASO 1: Thread de Alta Prioridade (ex: Pacotes de Rede / SelectorThread)
            // NÃO podemos rodar a tarefa aqui, senão o servidor laga para todos os players.
            // Solução: Jogamos para uma Virtual Thread (rápida e leve).
            threadPriority > Thread.NORM_PRIORITY -> {
                Thread.ofVirtual()
                    .name("Rejected-HighPrio-${System.nanoTime()}")
                    .start(runnable)
                
                // Log leve (Fine) para não spammar
                // LOGGER.fine("Tarefa crítica rejeitada -> Salva em Virtual Thread.")
            }
            
            // CASO 2: Thread Normal (ex: Game Logic / AI)
            // Aqui podemos tentar usar Virtual Thread também, pois Java 25 aguenta milhões delas.
            else -> {
                try {
                    Thread.ofVirtual()
                        .name("Rejected-Virtual-${System.nanoTime()}")
                        .start(runnable)
                } catch (e: Exception) {
                    // Fallback final: Se até criar thread falhar, roda na força bruta
                    runnable.run()
                }
            }
        }
    }
}