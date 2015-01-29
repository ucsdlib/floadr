package edu.ucsd.library.floadr;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;


/**
 * Utility to push RDF/XML files into Fedora 4.
 * @author escowles
 * @since 2015-01-28
**/
public class Pushr {

    private static String repositoryURL;
    private static HttpClient client;
    private static SAXReader parser = new SAXReader();

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: rdf/xml source directory
    **/
    public static void main( String[] args ) throws Exception {
        File sourceDir = new File(args[0]);

        PoolingClientConnectionManager pool = new PoolingClientConnectionManager();
        pool.setMaxTotal(Integer.MAX_VALUE);
        pool.setDefaultMaxPerRoute(Integer.MAX_VALUE);
        client = new DefaultHttpClient(pool);

        int records = 0;
        Set<String> errors = new HashSet<>();

        File[] sourceFiles = sourceDir.listFiles();
        long start = System.currentTimeMillis();
        for ( int i = 0; i < sourceFiles.length; i++ ) {
            records++;
            File sourceFile = sourceFiles[i];
            int status = loadFile( sourceFile );
            String msg = records + ": " + sourceFile.getName() + ", errors: ";
            if ( status != 204 ) {
                errors.add( sourceFile.getName() );
                msg += errors.size() + ", status: " + status;
            } else {
                msg += errors.size();
            }
            System.out.println(msg);
            if ( (i+1) % 100 == 0 ) {
                long now = System.currentTimeMillis();
                long dur = now - start;
                start = now;
                System.out.println("push: " + (i+1) + ": " + ((float)dur/1000));
            }
        }

        // report errors
        if ( errors.size() > 0 ) {
            System.out.println("errors:");
            for ( Iterator<String> it = errors.iterator(); it.hasNext(); ) {
                System.out.println("  " + it.next());
            }
        }
    }

    private static int loadFile( File f ) {
        int status = 0;
        HttpPut put = null;
        try {
            // parse xml file and get object URI
            Document doc = parser.read(f);
            Element e = (Element)doc.getRootElement().elementIterator().next();
            String objectURI = e.attributeValue("about");

            // create put request
            put = new HttpPut( objectURI );
            put.addHeader( "Content-Type", "application/rdf+xml" );
            put.addHeader( "Prefer", "handling=lenient; received=\"minimal\"" );
            put.setEntity( new ByteArrayEntity(doc.asXML().getBytes("utf-8")) );

            // execute update
            HttpResponse response = client.execute(put);
            status = response.getStatusLine().getStatusCode();
        } catch ( Exception ex ) {
            System.out.println("Error: " + ex.toString());
            status = -1;
        } finally {
            if ( put != null ) {
                put.releaseConnection();
            }
        }
        return status;
    }
}
