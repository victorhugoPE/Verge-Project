package ext.mods.commons.pool

import java.util.concurrent.*

/**
 * Representa um ScheduledFuture que já foi cancelado, usado como retorno em caso de erro
 * para evitar retornar 'null' no Kotlin.
 */
class ScheduledFutureTask private constructor() : ScheduledFuture<Void> {
    
    // Retorna 0 para o delay, pois a tarefa está "concluída" ou "cancelada"
    override fun getDelay(unit: TimeUnit): Long = 0
    
    // Sempre retorna 0, pois a ordem não importa para uma tarefa cancelada
    override fun compareTo(other: Delayed?): Int = 0 

    // Métodos de Future
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
    override fun isCancelled(): Boolean = true
    override fun isDone(): Boolean = true
    override fun get(): Void? = null
    override fun get(timeout: Long, unit: TimeUnit): Void? = null

    companion object {
        private val CANCELLED = ScheduledFutureTask()
        /**
         * Retorna uma instância de um ScheduledFuture cancelado.
         */
        @JvmStatic
        fun cancelled(): ScheduledFuture<*> = CANCELLED
    }
}