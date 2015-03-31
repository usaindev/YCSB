package com.yahoo.ycsb.couchbase;

import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;
import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.protocol.views.View;
import com.couchbase.client.protocol.views.Query;
import com.couchbase.client.protocol.views.ViewResponse;
import com.couchbase.client.CouchbaseConnectionFactory;
import com.couchbase.client.CouchbaseConnectionFactoryBuilder;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.memcached.MemcachedCompatibleClient;
import net.spy.memcached.PersistTo;
import net.spy.memcached.ReplicateTo;
import net.spy.memcached.internal.GetFuture;
import net.spy.memcached.internal.OperationFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;

@SuppressWarnings({"NullableProblems"})
public class CouchbaseClient2_0 extends MemcachedCompatibleClient {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected CouchbaseClient couchbaseClient;

    protected CouchbaseConfig couchbaseConfig;

    private static View view;

    private boolean checkOperationStatus;

    private long shutdownTimeoutMillis;

    private int objectExpirationTime;

    private Random generator = new Random();

    private Map<String, View> views = new HashMap<String, View>();

    private String[] ddoc_names;

    private String[] view_names;

    protected PersistTo persistTo = PersistTo.ZERO;

    protected ReplicateTo replicateTo = ReplicateTo.ZERO;

    @Override
    public void init() throws DBException {
        try {
            couchbaseConfig = createMemcachedConfig();
            couchbaseClient = createCouchbaseClient();
            client = couchbaseClient;
            checkOperationStatus = couchbaseConfig.getCheckOperationStatus();
            objectExpirationTime = couchbaseConfig.getObjectExpirationTime();
            shutdownTimeoutMillis = couchbaseConfig.getShutdownTimeoutMillis();
            ddoc_names = couchbaseConfig.getDdocs();
            view_names = couchbaseConfig.getViews();
            persistTo = couchbaseConfig.getPersistTo();
            replicateTo = couchbaseConfig.getReplicateTo();
            if (persistTo == null && replicateTo != null) {
                persistTo = PersistTo.ZERO;
            }
            if (replicateTo == null && persistTo != null) {
                replicateTo = ReplicateTo.ZERO;
            }
        } catch (Exception e) {
            throw new DBException(e);
        }
    }

    protected CouchbaseConfig createMemcachedConfig() {
        return new CouchbaseConfig(getProperties());
    }

    protected CouchbaseClient createMemcachedClient() throws Exception {
        return createCouchbaseClient();
    }

    protected CouchbaseClient createCouchbaseClient() throws Exception {
        CouchbaseConnectionFactoryBuilder builder = new CouchbaseConnectionFactoryBuilder();
        builder.setReadBufferSize(couchbaseConfig.getReadBufferSize());
        builder.setOpTimeout(couchbaseConfig.getOpTimeout());
        builder.setFailureMode(couchbaseConfig.getFailureMode());

        List<URI> servers = new ArrayList<URI>();
        for (String address : couchbaseConfig.getHosts().split(",")) {
            servers.add(new URI("http://" + address + ":8091/pools"));
        }
        CouchbaseConnectionFactory connectionFactory =
                builder.buildCouchbaseConnection(servers,
                        couchbaseConfig.getBucket(), couchbaseConfig.getUser(), couchbaseConfig.getPassword());
        return new com.couchbase.client.CouchbaseClient(connectionFactory);
    }

    @Override
    public int read(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
        try {
            GetFuture<Object> future = couchbaseClient.asyncGet(createQualifiedKey(table, key));
            Object document = future.get();
            if (document != null) {
                fromJson((String) document, fields, result);
            }
            return document != null ? OK : ERROR;
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Error encountered", e);
            }
            return ERROR;
        }
    }

    @Override
    public int update(String table, String key, HashMap<String, ByteIterator> values) {
        HashMap<String, ByteIterator> result = new HashMap<String, ByteIterator>();
        key = createQualifiedKey(table, key);
        Boolean success = false;
        int tries=0;
        while (tries<100 && success!=true) {
          try {
            CASValue<Object> future = couchbaseClient.gets(key);
            Object document = future.getValue();
            if (document == null) {
                    System.err.println("Document not found!");
                    return ERROR;
            }
            fromJson(document.toString(), null, result);
            // get cas value and use "compare and swap" rather than "replace" to avoid overwriting
            // any other updates made in the time since we fetched the document
            // REF: http://www.couchbase.com/autodocs/couchbase-java-client-1.1.8/com/couchbase/client/CouchbaseClient.html#cas(java.lang.String, long, java.lang.Object, net.spy.memcached.PersistTo, net.spy.memcached.ReplicateTo)
            // http://docs.couchbase.com/developer/dev-guide-3.0/update-info.html#concept29631__cas
            Long cas = future.getCas();
            CASResponse res = null;
            if (persistTo == null && replicateTo == null) {
                res = couchbaseClient.cas(key, cas, toJson(result));
            } else {
                res = couchbaseClient.cas(key, cas, toJson(result), persistTo, replicateTo);
            }
            if (res==CASResponse.EXISTS) {
                tries++;
                if (tries == 50) System.err.println("Warning, still haven't updated value with key: " + key + " in " + tries + " tries.");
            } else {
                success = true;
            }
          } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Error updating value with key: " + key, e);
            }
            e.printStackTrace();
            return ERROR;
           }
        }
        return success == true ? OK : ERROR;
    }

    @Override
    public int insert(String table, String key, HashMap<String, ByteIterator> values) {
        key = createQualifiedKey(table, key);
        try {
            OperationFuture<Boolean> future;
            if (persistTo == null && replicateTo == null) {
                future = couchbaseClient.add(key, objectExpirationTime, toJson(values));
            } else {
                future = couchbaseClient.add(key, objectExpirationTime, toJson(values),
                    persistTo, replicateTo);
            }
            return getReturnCode(future);
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Error inserting value", e);
            }
            return ERROR;
        }
    }

    @Override
    public int delete(String table, String key) {
        key = createQualifiedKey(table, key);
        try {
            OperationFuture<Boolean> future = client.delete(key);
            return getReturnCode(future);
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Error deleting value", e);
            }
            return ERROR;
        }
    }

    @Override
    public int query(String table, String key, int limit) {
        int rnd_ddoc = generator.nextInt(ddoc_names.length);
        int rnd_view = generator.nextInt(view_names.length);
        int startIndex = 3 * rnd_ddoc + rnd_view;
        try {
            key = "field" + startIndex + key.substring(4 + startIndex, 12 + startIndex);
        } catch (StringIndexOutOfBoundsException e) {
            key = "field" + startIndex;
        }

        Query query = get_query(key, limit);
        View view = get_view(rnd_ddoc, rnd_view);
        ViewResponse response = couchbaseClient.query(view, query);

        Collection errors = response.getErrors();
        if (errors.isEmpty()) {
            return OK;
        } else {
            return ERROR;
        }
    }

    private View get_view(int rnd_ddoc, int rnd_view) {
        String ddoc_name = ddoc_names[rnd_ddoc];
        String view_name = view_names[rnd_view];
        String id = ddoc_name + view_name;

        if (views.get(id) == null) {
            view = couchbaseClient.getView(ddoc_name, view_name);
            views.put(id, view);
        }

        return view;
    }

    private Query get_query(String key, int limit) {
        Query query = new Query();
        query.setRangeStart(key);
        query.setLimit(limit);
        return query;
    }

    protected int getReturnCode(OperationFuture<Boolean> future) {
        if (checkOperationStatus) {
            return future.getStatus().isSuccess() ? OK : ERROR;
        } else {
            return OK;
        }
    }
}
