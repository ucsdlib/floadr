package edu.ucsd.library.floadr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.DocumentResult;
import org.dom4j.io.XMLWriter;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;


/**
 * Utility to transform dams4 rdf to dams42, and output URLs of linked resources
 * @author escowles
 * @since 2015-01-27
**/
public class Transformr {

    private static int errors = 0;
    private static String repositoryURL;
    private static Transformer xslt;
    private static HttpClient client;
    private static Set<String> resourceSet = new TreeSet<>();
    private static int resourceCount = 0;
    private static Set<String> fileSet = new TreeSet<>();

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: dams4 rdf source directory
     *     1: dams42 rdf destination directory
     *     2: file URL list destination file
     *     3: repository base URL
    **/
    public static void main( String[] args ) throws Exception {
        File sourceDir = new File(args[0]);
        File destDir = new File(args[1]);
        File filesFile = new File(args[2]);
        repositoryURL = args[3];

        PoolingClientConnectionManager pool = new PoolingClientConnectionManager();
        pool.setMaxTotal(Integer.MAX_VALUE);
        pool.setDefaultMaxPerRoute(Integer.MAX_VALUE);
        client = new DefaultHttpClient(pool);

        // load dams4.2.xsl
        final StreamSource xsl = new StreamSource(Simploadr.class.getClassLoader()
                .getResourceAsStream("dams4.2.xsl"));
        xslt = TransformerFactory.newInstance().newTransformer(xsl);
        xslt.setParameter("repositoryURL", repositoryURL);

        // for each id, find/create a fedora object and then load each file
        int records = 0;
        final File[] sourceFiles = sourceDir.listFiles();
        for ( int i = 0; i < sourceFiles.length; i++ ) {
            records++;
            final File sourceFile = sourceFiles[i];
            loadMetadata( sourceFile, destDir );
            System.out.println(records + ": " + sourceFile.getName() + ", errors: " + errors);
        }

        // create resource stub records
        long start = System.currentTimeMillis();
        System.out.println("Creating resource stubs:");
        for ( Iterator<String> it = resourceSet.iterator(); it.hasNext(); ) {
            createResource(it.next());
            if ( resourceCount % 1000 == 0 ) {
                System.out.println("  " + resourceCount);
            }
        }
        long dur = System.currentTimeMillis() - start;
        System.out.println("created " + resourceCount + " resource stubs in " + (dur/1000) + " sec");

        // output file URLs
        System.out.println("Listing files:");
        PrintWriter fileout = new PrintWriter( new FileWriter(filesFile) );
        int fileCount = 0;
        for ( Iterator<String> it = fileSet.iterator(); it.hasNext(); ) {
            fileout.println( repositoryURL + it.next() );
            fileCount++;
            if ( fileCount % 1000 == 0 ) {
                System.out.println("  " + fileCount);
            }
        }
        fileout.close();
        System.out.println("wrote " + fileCount + " file URLs to " + filesFile.getName());
    }

    private static void createResource( String path ) {
        String objectURI = repositoryURL + path;
        HttpPut put = new HttpPut( objectURI );
        int status = 0;
        try {
            HttpResponse response = client.execute(put);
            status = response.getStatusLine().getStatusCode();
        } catch ( Exception ex ) {
            System.out.println("Error: " + ex.toString() + ", path: '" + path + "'");
            status = -1;
        } finally {
            put.releaseConnection();
        }
        if ( status == 201 ) {
            resourceCount++;
        } else {
            System.out.println("Node: " + path + ": " + status);
            errors++;
        }
    }

    private static void loadMetadata( File metaFile, File destDir ) {
        try {
            // xslt metadata
            final DocumentResult result = new DocumentResult();
            xslt.transform( new StreamSource(metaFile), result );
            Document doc = result.getDocument();

            // create linked records
            final List links = doc.selectNodes("//*[@rdf:resource]|//*[@rdf:about]");
            for ( Iterator it = links.iterator(); it.hasNext(); ) {
                Element e = (Element)it.next();
                Attribute about = e.attribute(0);
                String linkPath = about.getValue();
                if ( linkPath.startsWith(repositoryURL) ) {
                    linkPath = fixLink(linkPath, repositoryURL);
                    about.setValue(repositoryURL + linkPath);

                    if ( linkPath.indexOf("fcr:metadata") != -1 ) {
                        fileSet.add(linkPath.replaceAll("/fcr:metadata",""));
                    } else {
                        resourceSet.add(linkPath);
                    }
                }
            }

            File f = new File(destDir, metaFile.getName());
            if ( ! f.getParentFile().exists() ) {
                f.getParentFile().mkdirs();
                System.out.println(f.getParentFile().getAbsolutePath());
            }
            XMLWriter w = new XMLWriter( new FileWriter(f) );
            w.write(doc);
            w.close();

        } catch ( Exception ex ) {
            errors++;
        }
    }

    private static String fixLink( String path, String repositoryURL ) {
        String s = path.replaceAll(repositoryURL + "/", "");
        String[] parts = s.split("/",2);
        parts[0] = pairPath(parts[0]);
        String newPath = "/" + parts[0];
        if ( parts.length == 2 ) {
            newPath += parts[1];
        }
        return newPath;
    }

    /**
     * Convert a string into a pairpath directory tree.
    **/
    public static String pairPath( String s ) {
        if ( s == null ) { return null; }
        String result = "";
        int i = 0;
        while( i < (s.length() - 1) )
        {
            result += s.substring(i,i+2);
            result += "/";
            i += 2;
        }
        if ( s.length() > i )
        {
            result += s.substring(i) + "/";
        }
        return result;
    }
}
