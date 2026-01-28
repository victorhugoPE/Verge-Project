package ext.mods.commons.pool

/**
 * Envolve um Runnable para garantir que quaisquer exceções não tratadas (Throwables)
 * sejam capturadas e delegadas ao UncaughtExceptionHandler da thread atual.
 *
 * @property runnable a tarefa original a ser executada.
 */
class RunnableWrapper(private val runnable: Runnable) : Runnable {
    
    override fun run() {
        try {
            runnable.run()
        } catch (e: Throwable) {
            val t = Thread.currentThread()
            val h = t.uncaughtExceptionHandler
            
            // Se houver um UncaughtExceptionHandler registrado, usamos ele.
            if (h != null) {
                h.uncaughtException(t, e)
            }
            // Se não houver, a exceção será simplesmente propagada ou logada.
        }
    }
}