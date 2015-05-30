
package com.yahoo.ycsb.db;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import com.couchbase.client.java.PersistTo;
import com.couchbase.client.java.ReplicateTo;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.error.CASMismatchException;
import com.couchbase.client.java.view.*;
import com.yahoo.ycsb.*;
// import com.yahoo.ycsb.ByteIterator;
// import com.yahoo.ycsb.DBException;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * this client uses Couchbase Java SDK 2.1.1 and supports Couchbase Server 3.0
 */

public class CouchbaseClient extends DB {

    private static boolean checkOperationStatus = true;
    private int objectExpirationTime;
    private static Cluster cluster;
    private static Bucket bucket;
    private static String designDocumentName;
    private static PersistTo persistTo;
    private static ReplicateTo replicateTo;
    private static final int OK = 0;
    private static final int ERROR = 1;
    private static boolean writeall=false;

    @Override
    public void init() throws DBException {

      synchronized(this.getClass()) {
        if (cluster != null) return;
        cluster = CouchbaseCluster.create(getProperties().getProperty("hosts"));
        bucket = cluster.openBucket(getProperties().getProperty("bucket"), 30, TimeUnit.SECONDS);
        designDocumentName = getProperties().getProperty("designDocumentName");
        String persist = getProperties().getProperty("couchbase.persistTo","NONE");
        persistTo = PersistTo.valueOf(persist);
        System.err.println("PersistTo is set to  " + persistTo.toString());
        String replicate = getProperties().getProperty("couchbase.replicateTo","NONE");
        replicateTo = ReplicateTo.valueOf(replicate);
        System.err.println("ReplicateTo is set to  " + replicateTo.toString());
        writeall = Boolean.parseBoolean(getProperties().getProperty("writeallfields", "false"));
      }

    }

    @Override
    public void cleanup() throws DBException {
        // cluster.disconnect();
    }

    @Override
    public int read(String table, String key, Set<String> fields, HashMap<String, ByteIterator> result) {
        key = createQualifiedKey(table, key);
        try {
            JsonDocument document = bucket.get(key);
            if (document != null) {
               JsonObject content = document.content();
               if (fields == null) {
                    for (String field: content.getNames()) {
                        result.put(field, new StringByteIterator(content.get(field).toString()));
                    }
                } else {
                    for (String field : fields) {
                        // TODO: Is this conversion through StringByteIterator efficient?
                        result.put(field, new StringByteIterator(document.content().get(field).toString()));
                    }
                } 
                return OK;
            } else {
                System.err.println("Got null document from get. Document not found.  key " + key);
                return ERROR;
            }
        } catch (Exception e) {
            System.err.println("read: Document not found.  key " + key);
            e.printStackTrace();
            return ERROR;
        }
    }

    @Override
    public int update(String table, String key, HashMap<String, ByteIterator> values) {
        /* if writeallfields was set then we're replacing the entire document */
        if (writeall) {
             return insert(table, key, values); 
        }
        /* otherwise we're passed in a single random field to update on the existing document */
        key = createQualifiedKey(table, key);
        String f=null;
        String v=null;
        for (Map.Entry entry : values.entrySet()) {
            f=entry.getKey().toString();
            v=entry.getValue().toString();
        }
        // Update the document catching CAS exceptions, retry once per ~millisecond if CASMismatchException is raised
        // as suggested by http://docs.couchbase.com/developer/java-2.1/documents-updating.html
        int tries=0;
        while (true) {
            JsonDocument document = bucket.get(key);
            if (document == null) {
                System.err.println("update: null Document returned.  key " + key);
                return ERROR;
            }
            Long cas = document.cas();
            document.content().put(f, v);
            try {
                if ( null != bucket.replace(document, persistTo, replicateTo, 20, TimeUnit.SECONDS) ) return OK;
                else {
                    System.err.println("update: null returned by replace for key " + key);
                    return ERROR;
                }
            } catch (CASMismatchException ec) {
                tries++;
                if (tries>2) try { Thread.sleep(1); } catch (InterruptedException ie) { }
                if (tries>50) try { Thread.sleep(5); } catch (InterruptedException ie) { }
                if (tries%200==0) System.err.println("update conflict try " + tries + " for key " + key);
            } catch (Exception e) {
                System.err.println("update: Exception handling  key " + key);
                e.printStackTrace();
                return ERROR;
            }
        }
    }

    @Override
    public int insert(String table, String key, HashMap<String, ByteIterator> values) {
        JsonObject content;
        key = createQualifiedKey(table, key);
        try {
            Iterator<Map.Entry<String, ByteIterator>> entries = values.entrySet().iterator();
            content = JsonObject.empty();
            while (entries.hasNext()) {
                Map.Entry<String, ByteIterator> entry = entries.next();
                content.put(entry.getKey(), entry.getValue().toString());
            }
            JsonDocument document = JsonDocument.create(key, objectExpirationTime, content);
            JsonDocument newDoc = bucket.upsert(document, persistTo, replicateTo, 20, TimeUnit.SECONDS);
            return OK;
        } catch (Exception e) {
            System.err.println("Document not inserted.  key " + key);
            e.printStackTrace();
            return ERROR;
        }
    }

    @Override
    public int scan(String table, String startkey, int recordcount, Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
        String qk = createQualifiedKey(table, startkey);
        ViewResult rows = bucket.query(ViewQuery.from(designDocumentName, table).stale(Stale.FALSE).startKey(qk).limit(recordcount));

        for (ViewRow row : rows) {
            JsonObject object = row.document().content();
            HashMap<String, ByteIterator> rowMap = new HashMap<String, ByteIterator>();
            for (String name : object.getNames()) {
                rowMap.put(name, new StringByteIterator(object.get(name).toString()));
            }
            result.add(rowMap);
        }
        return OK;
    }

    @Override
    public int delete(String table, String key) {
        key = createQualifiedKey(table, key);
        try {
            bucket.remove(key);
            return OK;
        } catch (Exception e) {
            e.printStackTrace();
            return ERROR;
        }
    }

    private String createQualifiedKey(String table, String key) {
        return table + "-" + key;
    }

}
