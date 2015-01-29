package edu.ucsd.library.floadr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.activation.MimetypesFileTypeMap;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;


/**
 * Utility to upload files in Fedora 4.
 * @author escowles
 * @since 2015-01-29
**/
public class Pokr {

    private static String repositoryURL;
    private static HttpClient client;
    private static MimetypesFileTypeMap mimeTypes = new MimetypesFileTypeMap();

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: list of URLs to create
     *                                     1: base directory of files (optional)
    **/
    public static void main( String[] args ) throws Exception {
        File urlFile = new File(args[0]);
        File baseDir = null;
        if ( args.length > 1 ) {
            baseDir = new File(args[1]);
        }

        PoolingClientConnectionManager pool = new PoolingClientConnectionManager();
        pool.setMaxTotal(Integer.MAX_VALUE);
        pool.setDefaultMaxPerRoute(Integer.MAX_VALUE);
        client = new DefaultHttpClient(pool);

        int records = 0;
        Set<String> errors = new HashSet<>();
        long start = System.currentTimeMillis();
        BufferedReader in = new BufferedReader( new FileReader(urlFile) );
        for ( String url = null; (url = in.readLine()) != null; ) {
            records++;
            int status = poke( url, findFile(url, baseDir) );
            if ( status != 201 ) {
                System.out.println(url + ", status: " + status + ", errors: " + errors.size());
            }
            if ( records % 100 == 0 ) {
                long now = System.currentTimeMillis();
                long dur = now - start;
                start = now;
                System.out.println("poke: " + records + ": " + ((float)dur/1000));
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

    private static int poke( String url, File f ) {
        int status = 0;
        HttpPut put = null;
        try {
            // create put request
            put = new HttpPut( url );
            if ( f != null ) {
                put.addHeader("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
                put.addHeader("Content-Type", mimeTypes.getContentType(f));
                put.setEntity( new FileEntity(f) );
            } else {
                put.setEntity( new ByteArrayEntity("dummy".getBytes("utf-8")) );
            }
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
    private static File findFile( String url, File baseDir ) {
        if ( baseDir == null ) {
            return null;
        }
        String path = url.substring( url.indexOf("rest") + 5 );
        String org = "20775";
        String file = path.substring( 15 );
        if ( file.indexOf("/") != -1 ) { file = file.replaceAll("/","-"); }
        else { file = "0-" + file; }
        path = path.substring( 0, 15 );
        String ark = path.replaceAll("/","");
        File f = new File( baseDir, path + org + "-" + ark + "-" + file );
        if ( f.exists() ) {
            return f;
        } else {
            return null;
        }
    }

}
