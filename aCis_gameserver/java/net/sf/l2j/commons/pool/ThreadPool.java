package net.sf.l2j.commons.pool;

import java.util.concurrent.ScheduledFuture;
import ext.mods.commons.pool.CoroutinePool;

/**
 * ThreadPool Bridge.
 * Conecta o antigo sistema Java ao novo sistema Kotlin (CoroutinePool).
 */
public class ThreadPool
{
    public static void init()
    {
        CoroutinePool.init();
    }

    /**
     * Agenda uma acao unica apos um delay.
     * @param r : A tarefa (Runnable) a ser executada.
     * @param delay : O tempo de espera em milissegundos.
     * @return ScheduledFuture
     */
    public static ScheduledFuture<?> schedule(Runnable r, long delay)
    {
        // Passamos o Runnable direto, pois o Kotlin ja trata excecoes
        return CoroutinePool.schedule(r, delay);
    }

    /**
     * Agenda uma acao periodica.
     * @param r : A tarefa (Runnable) a ser executada.
     * @param delay : O tempo inicial de espera.
     * @param period : O intervalo entre execucoes.
     * @return ScheduledFuture
     */
    public static ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long delay, long period)
    {
        return CoroutinePool.scheduleAtFixedRate(r, delay, period);
    }

    /**
     * Executa uma tarefa instantanea.
     * @param r : A tarefa (Runnable).
     */
    public static void execute(Runnable r)
    {
        CoroutinePool.execute(r);
    }

    /**
     * Desliga o pool de threads.
     */
    public static void shutdown()
    {
        CoroutinePool.shutdown();
    }
}