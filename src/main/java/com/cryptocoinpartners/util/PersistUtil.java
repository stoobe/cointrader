package com.cryptocoinpartners.util;

import com.cryptocoinpartners.schema.*;
import com.cryptocoinpartners.schema.Currency;

import javax.persistence.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;


/**
 * @author Tim Olson
 */
public class PersistUtil {

    public static void insert(EntityBase... entities) {
        EntityManager em = null;
        try {
            em = createEntityManager();
            EntityTransaction transaction = em.getTransaction();
            transaction.begin();
            try {
                for( EntityBase entity : entities )
                    em.persist(entity);
                transaction.commit();
            }
            catch( Error t ) {
                transaction.rollback();
                throw t;
            }
        }
        finally {
            if( em != null )
                em.close();
        }
    }


    public static interface EntityHandler<T extends EntityBase> {
        /**
         @param row an entity returned from the queryEach
         @return true to continue with the next result row, or false to halt iteration
         @see #queryEach(Class, com.cryptocoinpartners.util.PersistUtil.EntityHandler, int, String, Object...)
         */
        boolean handleEntity(T row);
    }


    public static <T extends EntityBase> void queryEach( Class<T> resultType, EntityHandler<T> handler,
                                                         String queryStr, Object... params ) {
        queryEach(resultType,handler,20,queryStr,params);
    }


    public static <T extends EntityBase> void queryEach( Class<T> resultType, EntityHandler<T> handler, int batchSize,
                                                         String queryStr, Object... params ) {
        EntityManager em = null;
        try {
            em = createEntityManager();
            final TypedQuery<T> query = em.createQuery(queryStr, resultType);
            if( params != null ) {
                for( int i = 0; i < params.length; i++ ) {
                    Object param = params[i];
                    query.setParameter(i+1,param); // JPA uses 1-based indexes
                }
            }
            query.setMaxResults(batchSize);
            for( int start = 0; ; start += batchSize ) {
                query.setFirstResult(start);
                final List<T> list = query.getResultList();
                if( list.isEmpty() )
                    return;
                for( T row : list ) {
                    if( !handler.handleEntity(row) )
                        return;
                }
            }
        }
        finally {
            if( em != null )
                em.close();
        }
    }


    public static <T extends EntityBase> List<T> queryList( Class<T> resultType, String queryStr, Object... params ) {
        EntityManager em = null;
        try {
            em = createEntityManager();
            final TypedQuery<T> query = em.createQuery(queryStr, resultType);
            if( params != null ) {
                for( int i = 0; i < params.length; i++ ) {
                    Object param = params[i];
                    query.setParameter(i+1,param); // JPA uses 1-based indexes
                }
            }
            return query.getResultList();
        }
        finally {
            if( em != null )
                em.close();
        }
    }


    /**
     returns a single result entity.  if none found, a javax.persistence.NoResultException is thrown.
     */
    public static <T extends EntityBase> T queryOne( Class<T> resultType, String queryStr, Object... params )
        throws NoResultException
    {
        EntityManager em = null;
        try {
            em = createEntityManager();
            final TypedQuery<T> query = em.createQuery(queryStr,resultType);
            if( params != null ) {
                for( int i = 0; i < params.length; i++ ) {
                    Object param = params[i];
                    query.setParameter(i+1,param); // JPA uses 1-based indexes
                }
            }
            return query.getSingleResult();
        }
        finally {
            if( em != null )
                em.close();
        }
    }


    /**
     returns a single result entity or null if not found
     */
    public static <T extends EntityBase> T queryZeroOne( Class<T> resultType, String queryStr, Object... params ) {
        EntityManager em = null;
        try {
            em = createEntityManager();
            final TypedQuery<T> query = em.createQuery(queryStr,resultType);
            if( params != null ) {
                for( int i = 0; i < params.length; i++ ) {
                    Object param = params[i];
                    query.setParameter(i+1,param); // JPA uses 1-based indexes
                }
            }
            try {
                return query.getSingleResult();
            }
            catch( NoResultException x ) {
                return null;
            }
        }
        finally {
            if( em != null )
                em.close();
        }
    }


    public static EntityManager createEntityManager() {
        init(false);
        return entityManagerFactory.createEntityManager();
    }


    static {
        MarketListing.class.getClass();
    }


    public static void resetDatabase() {
        init(true);
    }


    private static void init(boolean resetDatabase) {
        if( generatingDefaultData )
            return;
        if( entityManagerFactory != null && !resetDatabase )
            return;
        if( resetDatabase )
            generatingDefaultData = true;

        Map<String,String> properties = new HashMap<String, String>();
        String createMode;
        if(resetDatabase)
            createMode = "create";
        else
            createMode = "update";
        properties.put("hibernate.hbm2ddl.auto",createMode);
        properties.put("hibernate.connection.driver_class", Config.get().getString("db.driver"));
        properties.put("hibernate.dialect", Config.get().getString("db.dialect"));
        properties.put("hibernate.connection.url", Config.get().getString("db.url"));
        properties.put("hibernate.connection.username", Config.get().getString("db.username"));
        properties.put("hibernate.connection.password", Config.get().getString("db.password"));

        try {
            entityManagerFactory = Persistence.createEntityManagerFactory("com.cryptocoinpartners.schema", properties);
            if( resetDatabase ) {
                loadDefaultData();
                generatingDefaultData = false;
            }
        }
        catch( Throwable t ) {
            if( entityManagerFactory != null ) {
                entityManagerFactory.close();
                entityManagerFactory = null;
            }
            throw new Error("Could not initialize db",t);
        }
    }


    /**
     * This flag is used by Currency to know whether to generate fields with new Currency objects or read them from
     * the existing rows in the database.
     * @see Currency
     */
    public static boolean generatingDefaultData = false;


    private static void loadDefaultData() {
        generatingDefaultData = true;

        loadStaticFieldsFromClass(Currency.class);
        loadStaticFieldsFromClass(Market.class);
        loadStaticFieldsFromClass(Listing.class);

        generatingDefaultData = true;
    }


    /**
     * Finds all static member fields which have the same type as the containing class, then loads those static
     * members as rows in the db
     * @param cls the class to inspect
     */
    private static <T extends EntityBase> void loadStaticFieldsFromClass(Class<T> cls) {
        List<EntityBase> all = new ArrayList<EntityBase>();
        Field[] fields = cls.getDeclaredFields();
        for( Field field : fields ) {
            if( Modifier.isStatic(field.getModifiers()) && cls.isAssignableFrom(field.getType()) ) {
                try {
                    all.add((EntityBase) field.get(null));
                }
                catch( IllegalAccessException e ) {
                    throw new Error("Could not read "+ cls.getSimpleName()+" field "+field,e);
                }
            }
        }
        PersistUtil.insert(all.toArray(new EntityBase[all.size()]));
    }


    private static EntityManagerFactory entityManagerFactory;
}
