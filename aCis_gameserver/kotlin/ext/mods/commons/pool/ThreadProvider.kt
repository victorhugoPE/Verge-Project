package ext.mods.commons.pool

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fornece uma implementação de ThreadFactory personalizável para nomear e configurar threads.
 * 
 * Compatível com a implementação Java original (ThreadProvider.java).
 * 
 * @property prefix o prefixo a ser usado para os nomes das threads.
 * @property priority a prioridade das threads (padrão: NORM_PRIORITY).
 * @property isDaemon whether the threads should be daemon threads (padrão: false).
 */
class ThreadProvider @JvmOverloads constructor(
    private val prefix: String,
    private val priority: Int = Thread.NORM_PRIORITY,
    private val isDaemon: Boolean = false
) : ThreadFactory {
    
    private val id: AtomicInteger = AtomicInteger() 
    private val threadPrefix: String = "$prefix "

    /**
     * Cria uma nova Thread com o objeto Runnable especificado e com as propriedades definidas.
     */
    override fun newThread(runnable: Runnable): Thread {
        val thread = Thread(runnable, threadPrefix + id.incrementAndGet())
        thread.priority = priority
        thread.isDaemon = isDaemon
        return thread
    }
}

