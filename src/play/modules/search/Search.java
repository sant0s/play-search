package play.modules.search;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.Id;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.FSDirectory;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.data.binding.Binder;
import play.db.jpa.JPA;
import play.db.jpa.JPASupport;
import play.db.jpa.Model;
import play.exceptions.UnexpectedException;

/**
 * Very basic tool to basic search on your JPA objects.
 * <p/>
 * On a JPASupport or JPAModel subclass, add the @Indexed annotation on your class, and the @Field
 * annotation on your field members
 * <p/>
 * Each time you save, update or delete your class, the corresponding index is
 * updated
 * <p/>
 * use the search method to query an index.
 * <p/>
 * Samples in samples-and-tests/app/controllers/JPASearch.java
 */
public class Search {

    private static Map<String, IndexWriter> indexWriters = new HashMap<String, IndexWriter>();
    private static Map<String, IndexSearcher> indexReaders = new HashMap<String, IndexSearcher>();

    public static String DATA_PATH;
    private static String ANALYSER_CLASS;
    public static boolean sync = true;

    public static void init() {
        try {
            shutdown();
        } catch (Exception e) {
            Logger.error(e, "Error while shutting down search module");
        }

        ANALYSER_CLASS = Play.configuration.getProperty("play.search.analyser", "org.apache.lucene.analysis.standard.StandardAnalyzer");
        if (Play.configuration.containsKey("play.search.path"))
            DATA_PATH = Play.configuration.getProperty("play.search.path");
        else
            DATA_PATH = Play.applicationPath.getAbsolutePath() + "/data/search/";
        Logger.trace("Search module repository is in " + DATA_PATH);
        Logger.trace("Write operations synch: " + sync);
        sync = Boolean.parseBoolean(Play.configuration.getProperty("play.search.synch", "true"));
    }

    private static Analyzer getAnalyser() {
        try {
            Class clazz = Class.forName(ANALYSER_CLASS);
            return (Analyzer) clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class SearchException extends RuntimeException {
        public SearchException(String message, Throwable cause) {
            super(message, cause);
        }

        public SearchException(Throwable cause) {
            super(cause);
        }

        public SearchException(String message) {
            super(message);
        }
    }

    public static class QueryResult {
        public String id;
        public float score;
        public JPASupport object;
    }

    public static class Query {
        private Class clazz;
        private String query;
        private String[] order = new String[0];
        private int offset = 0;
        private int pageSize = 10;
        private boolean reverse = false;
        private Hits hits = null;

        protected Query(String query, Class clazz) {
            this.query = query;
            this.clazz = clazz;
        }

        public Query page(int offset, int pageSize) {
            this.offset = offset;
            this.pageSize = pageSize;
            return this;
        }

        public Query all() {
            pageSize = -1;
            return this;
        }

        public Query reverse() {
            this.reverse = true;
            return this;
        }

        public Query orderBy(String... order) {
            this.order = order;
            return this;
        }

        private Sort getSort() {
            Sort sort = new Sort();
            if (order.length > 0) {
                if (reverse) {
                    if (order.length != 1)
                        throw new SearchException("reverse can be used while sorting only one field with oderBy");
                    else
                        sort.setSort(order[0], reverse);
                } else
                    sort.setSort(order);
            }
            return sort;
        }

        /**
         * Executes the query and return directly JPASupport objects (No score
         * information)
         *
         * @return
         */
        public <T extends JPASupport> List<T> fetch() throws SearchException {
            try {
                List<QueryResult> results = executeQuery(true);
                List<JPASupport> objects = new ArrayList<JPASupport>();
                for (QueryResult queryResult : results) {
                    objects.add(queryResult.object);
                }
                return (List) objects;
            } catch (Exception e) {
                throw new UnexpectedException(e);
            }
        }

        public List<Long> fetchIds() throws SearchException {
            try {
                List<QueryResult> results = executeQuery(false);
                List<Long> objects = new ArrayList<Long>();
                for (QueryResult queryResult : results) {
                    objects.add(Long.parseLong(queryResult.id));
                }
                return objects;
            } catch (Exception e) {
                throw new UnexpectedException(e);
            }
        }

        public long count() throws SearchException {
            try {
                org.apache.lucene.search.Query luceneQuery = new QueryParser("_docID", getAnalyser()).parse(query);
                hits = getIndexReader(clazz.getName()).search(luceneQuery, getSort());
                return hits.length();
            } catch (ParseException e) {
                throw new SearchException(e);
            } catch (Exception e) {
                throw new UnexpectedException(e);
            }
        }

        /**
         * Executes the lucene query against the index. You get QueryResults.
         *
         * @param fetch load the corresponding JPASupport objects in the QueryResult
         *              Object
         * @return
         */
        public List<QueryResult> executeQuery(boolean fetch) throws SearchException {
            try {
                if (hits == null) {
                    org.apache.lucene.search.Query luceneQuery = new QueryParser("_docID", getAnalyser()).parse(query);
                    hits = getIndexReader(clazz.getName()).search(luceneQuery, getSort());
                }
                List<QueryResult> results = new ArrayList<QueryResult>();
                if (hits == null)
                    return results;

                int l = hits.length();
                if (offset > l) {
                    return results;
                }
                List<Long> ids = new ArrayList<Long>();
                if (pageSize > 0) {
                    for (int i = offset; i < (offset + pageSize > l ? l : offset + pageSize); i++) {
                        QueryResult qresult = new QueryResult();
                        qresult.score = hits.score(i);
                        qresult.id = hits.doc(i).get("_docID");
                        if (fetch) {
                            // Maybe we should check the ID type. Here it might not work if id=Long
                            // qresult.object = (Model) JPA.em().find(clazz, Long.parseLong(qresult.id) );
                            Object objectId = getIdValueFromIndex (clazz,qresult.id);
                            qresult.object = (JPASupport) JPA.em().find(clazz, objectId);
                            if (qresult.object == null)
                                throw new SearchException("Please re-index");
                        }
                        results.add(qresult);
                    }
                } else {
                    for (int i = 0; i < l; i++) {
                        QueryResult qresult = new QueryResult();
                        qresult.score = hits.score(i);
                        qresult.id = hits.doc(i).get("_docID");
                        if (fetch) {
                            Object objectId = getIdValueFromIndex (clazz,qresult.id);
                            qresult.object = (JPASupport) JPA.em().find(clazz, objectId);
                            if (qresult.object == null)
                                throw new SearchException("Please re-index");
                        }
                        results.add(qresult);
                    }
                }
                return results;
            } catch (ParseException e) {
                throw new SearchException(e);
            } catch (Exception e) {
                throw new UnexpectedException(e);
            }
        }
    }

    public static Query search(String query, Class clazz) {
        return new Query(query, clazz);
    }

    public static void unIndex(Object object) {
        try {
            if (!(object instanceof JPASupport))
                return;
            if (object.getClass().getAnnotation(Indexed.class) == null)
                return;
            JPASupport jpaSupport = (JPASupport) object;
            String index = object.getClass().getName();
            getIndexWriter(index).deleteDocuments(new Term("_docID", getIdValueFor(jpaSupport) + ""));
            if (sync) {
                getIndexWriter(index).flush();
                dirtyReader(index);
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public static void index(Object object) {
        try {
            if (!(object instanceof JPASupport)) {
                Logger.warn("Unable to index " + object + ", unsupported class type. Only play.db.jpa.Model or  play.db.jpa.JPASupport classes are supported.");
                return;
            }
            JPASupport jpaSupport = (JPASupport) object;
            String index = object.getClass().getName();
            Document document = toDocument(object);
            if (document == null)
                return;
            getIndexWriter(index).deleteDocuments(new Term("_docID", getIdValueFor(jpaSupport) + ""));
            getIndexWriter(index).addDocument(document);
            if (sync) {
                getIndexWriter(index).flush();
                dirtyReader(index);
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    private static Document toDocument(Object object) throws Exception {
        Indexed indexed = object.getClass().getAnnotation(Indexed.class);
        if (indexed == null)
            return null;
        if (!(object instanceof JPASupport))
            return null;
        JPASupport jpaSupport = (JPASupport) object;
        Document document = new Document();
        document.add(new Field("_docID", getIdValueFor(jpaSupport) + "", Field.Store.YES, Field.Index.UN_TOKENIZED));
        for (java.lang.reflect.Field field : object.getClass().getFields()) {
            play.modules.search.Field index = field.getAnnotation(play.modules.search.Field.class);
            if (index == null)
                continue;
            if (field.getType().isArray())
                continue;
            if (field.getType().isAssignableFrom(Collection.class))
                continue;

            String name = field.getName();
            String value = valueOf(object, field);

            if (value == null)
                continue;

            document.add(new Field(name, value, index.stored() ? Field.Store.YES : Field.Store.NO, index.tokenize() ? Field.Index.TOKENIZED
                    : Field.Index.UN_TOKENIZED));
        }
        return document;
    }

    private static String valueOf(Object object, java.lang.reflect.Field field) throws Exception {
        if (field.getType().equals(String.class))
            return (String) field.get(object);
        return "" + field.get(object);
    }

    public static IndexSearcher getIndexReader(String name) {
        try {
            if (!indexReaders.containsKey(name)) {
                synchronized (Search.class) {
                    File root = new File(DATA_PATH, name);
                    if (root.exists()) {
                        IndexSearcher reader = new IndexSearcher(FSDirectory.getDirectory(root));
                        indexReaders.put(name, reader);
                    } else
                        throw new UnexpectedException("Could not find " + name + " index. Please re-index");
                }
            }
            return indexReaders.get(name);
        } catch (Exception e) {
            throw new UnexpectedException("Cannot open index", e);
        }
    }

    /**
     * Used to synchronize reads after write
     *
     * @param name of the reader to be reopened
     */
    public static void dirtyReader(String name) {
        synchronized (Search.class) {
            try {
                if (indexReaders.containsKey(name)) {
                    IndexReader rd = indexReaders.get(name).getIndexReader().reopen();
                    indexReaders.get(name).close();
                    indexReaders.remove(name);
                    indexReaders.put(name, new IndexSearcher(rd));
                }
            } catch (IOException e) {
                throw new UnexpectedException("Can't reopen reader", e);
            }
        }
    }

    private static IndexWriter getIndexWriter(String name) {
        try {
            if (!indexWriters.containsKey(name)) {
                synchronized (Search.class) {
                    File root = new File(DATA_PATH, name);
                    if (!root.exists())
                        root.mkdirs();
                    if (new File(root, "write.lock").exists())
                        new File(root, "write.lock").delete();
                    IndexWriter writer = new IndexWriter(FSDirectory.getDirectory(root), true, getAnalyser());
                    indexWriters.put(name, writer);
                }
            }
            return indexWriters.get(name);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public static void reindex() throws Exception {
        shutdown();
        File fl = new File(DATA_PATH);
        FileUtils.deleteDirectory(fl);
        fl.mkdirs();
        List<ApplicationClass> classes = Play.classes.getAnnotatedClasses(Indexed.class);
        for (ApplicationClass applicationClass : classes) {
            List<JPASupport> objects = (List<JPASupport>) JPA.em().createQuery(
                    "select e from " + applicationClass.javaClass.getCanonicalName() + " as e").getResultList();
            for (JPASupport jpaSupport : objects) {
                index(jpaSupport);
            }
        }
        Logger.info("Rebuild index finished");
    }

    public static void shutdown() throws Exception {
        for (IndexWriter writer : indexWriters.values()) {
            writer.close();
        }
        for (IndexSearcher searcher : indexReaders.values()) {
            searcher.close();
        }
        indexWriters.clear();
        indexReaders.clear();
    }
    /**
     * Looks for the type of the id fiels on the JPASupport target class
     * and use play's binder to retrieve the corresponding object
     * used to build JPA load query
     * @param clazz JPASupport target class
     * @param indexValue String value of the id, taken from index
     * @return Object id expected to build query
     */
    private static Object getIdValueFromIndex (Class clazz, String indexValue) {
        java.lang.reflect.Field field = getIdField(clazz);
        Class parameter = field.getType();
        try {
            return Binder.directBind(indexValue, parameter);
        } catch (Exception e) {
            throw new UnexpectedException("Could not convert the ID from index to corresponding type",e);
        }
    }

    /**
     * Find a ID field on the JPASupport target class
     * @param clazz JPASupport target class
     * @return corresponding field
     */
    private static java.lang.reflect.Field getIdField (Class clazz) {
        for (java.lang.reflect.Field field : clazz.getFields()) {
            if (field.getAnnotation(Id.class) != null) {
                return field;
            }
        }
        throw new RuntimeException("Your class " + clazz.getName() + " is annotated with javax.persistence.Id but the field Id was not found");
    }

    /**
     * Lookups the id field, being a Long id for Model and an annotated field @Id for JPASupport
     * and returns the field value.
     *
     * @param jpaSupport is a Play! Framework that supports JPA
     * @return the field value (a Long or a String for UUID)
     */
    private static Object getIdValueFor(JPASupport jpaSupport) {
        if (jpaSupport instanceof Model) {
            return ((Model) jpaSupport).id;
        }

        java.lang.reflect.Field field = getIdField(jpaSupport.getClass());
        Object val = null;
        try {
            val = field.get(jpaSupport);
        } catch (IllegalAccessException e) {
            Logger.error("Unable to read the field value of a field annotated with @Id " + field.getName() + " due to " + e.getMessage(), e);
        }
        return val;
    }
}
