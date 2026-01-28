package net.sf.l2j.commons.pool;

import java.sql.Connection;
import java.sql.SQLException;

import net.sf.l2j.commons.logging.CLogger;
import net.sf.l2j.Config;

import org.mariadb.jdbc.MariaDbPoolDataSource;

public final class ConnectionPool
{
    private static final CLogger LOGGER = new CLogger(ConnectionPool.class.getName());
    
    private static MariaDbPoolDataSource _source;
    
    private ConnectionPool()
    {
        throw new IllegalStateException("Utility class");
    }
    
    public static void init()
    {
        // Se ja estiver inicializado, ignora para evitar reset acidental
        if (_source != null)
        {
            return;
        }

        try
        {
            _source = new MariaDbPoolDataSource();
            
            // Configuracoes de Login
            if (!Config.DATABASE_LOGIN.isEmpty())
            {
                _source.setUser(Config.DATABASE_LOGIN);
                _source.setPassword(Config.DATABASE_PASSWORD);
            }
            
            // Define a URL por ultimo para garantir que o pool inicialize com os parametros corretos
            _source.setUrl(Config.DATABASE_URL);
            
            // Testa a conexao imediatamente para garantir que o servidor nao inicie com DB quebrado
            try (Connection con = _source.getConnection())
            {
                if (!con.isValid(5))
                {
                    throw new SQLException("Connection validation failed.");
                }
            }
            
            LOGGER.info("Initializing ConnectionPool: Connected successfully.");
        }
        catch (SQLException e)
        {
            LOGGER.error("CRITICAL: Couldn't initialize connection pool.", e);
            // Isso forca o servidor a parar se o banco nao conectar.
            // Eh melhor parar agora do que travar tudo depois.
            throw new RuntimeException("Database initialization failed.", e);
        }
    }
    
    public static void shutdown()
    {
        if (_source != null)
        {
            try 
            {
                _source.close();
            }
            catch (Exception e)
            {
                LOGGER.warn("Error while shutting down ConnectionPool.", e);
            }
            finally 
            {
                _source = null;
            }
            LOGGER.info("ConnectionPool shutdown completed.");
        }
    }
    
    public static Connection getConnection() throws SQLException
    {
        if (_source == null)
        {
            throw new SQLException("ConnectionPool is not initialized.");
        }
        return _source.getConnection();
    }
}