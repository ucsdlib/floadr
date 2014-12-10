package edu.ucsd.library.floadr;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;

import java.util.Iterator;
import java.util.List;

import javax.activation.MimetypesFileTypeMap;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stream.StreamResult;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.DocumentResult;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.util.EntityUtils;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.StmtIterator;

/**
 * Simple fedora loading utility
 * @author escowles
 * @since 2014-11-17
**/
public class Simploadr {

    private static MimetypesFileTypeMap mimeTypes = new MimetypesFileTypeMap();
    private static int errors = 0;
    private static int recordsUpdated = 0;
    private static int filesCreated = 0;
    private static int filesSkipped = 0;

    private static String repositoryURL;
    private static HttpClient client;
    private static Transformer xslt;

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: Repository baseURL,
     *     1: File listing object IDs (one per line),
     *     2: Base directory to find source files.
    **/
    public static void main( String[] args ) throws Exception {
        repositoryURL = args[0];
        File objectIds = new File(args[1]);
        File sourceDir = new File(args[2]);

        String mode = "meta";
        if ( args.length > 3 ) { mode = args[3]; }
        boolean files = (mode != null && mode.indexOf("files") != -1);
        boolean meta  = (mode != null && mode.indexOf("meta")  != -1);
        boolean merge = (mode != null && mode.indexOf("merge") != -1);

        PoolingClientConnectionManager pool = new PoolingClientConnectionManager();
        pool.setMaxTotal(Integer.MAX_VALUE);
        pool.setDefaultMaxPerRoute(Integer.MAX_VALUE);
        client = new DefaultHttpClient(pool);

        // load dams5.xsl
        final StreamSource xsl = new StreamSource(Simploadr.class.getClassLoader()
                .getResourceAsStream("dams5.xsl"));
        xslt = TransformerFactory.newInstance().newTransformer(xsl);
        xslt.setParameter("repositoryURL", repositoryURL);

        // for each id, find/create a fedora object and then load each file
        BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        int records = 0;
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            records++;
            final String pairPath = pairPath(id);
            final File objDir = new File( sourceDir, pairPath );
            final File[] objFiles = objDir.listFiles();
            if ( objFiles.length > 0 ) {

                // create object
                createObject( objPath(id) );

                // load files
                if ( files ) {
                    for ( File f : objFiles ) {
                        createFile( id, f );
                    }
                }

                // load metadata
                if ( meta ) {
                    File metaFile = new File( sourceDir + "/" + pairPath(id) + "20775-" + id
                            + "-0-rdf.xml" );
                    loadMetadata( id, metaFile, merge );
                }
            }
            System.out.println(records + ": " + id + ", metadata: " + recordsUpdated + ", files: " + filesCreated + ", skipped: " + filesSkipped + ", errors: " + errors);
        }
    }

    private static void createObject( String path ) {
        String objectURI = repositoryURL + path;
        if ( !exists(path) ) {
            HttpPut put = new HttpPut( objectURI );
            int status = execute(put, true);
            if ( status != 201 ) {
                System.out.println("Node: " + path + ": " + status);
                errors++;
            }
        }
    }
    private static void createFile( String objid, File dsFile ) {
        String objectURI = repositoryURL + "/" + objPath(objid);
        try {
            final String fn = dsFile.getName();
            if ( fn.matches("^20775-" + objid + "-\\d-.*") && !fn.endsWith("rdf.xml") ) {
                final String fileId = fileId( objid, fn );
                final String dsPath = objPath(objid) + fileId;
                String fileURI = repositoryURL + dsPath;
                if ( exists(dsPath) ) {
                    System.out.println("  Skipped: " + fn);
                    filesSkipped++;
                } else {
                    HttpPut put = new HttpPut( fileURI );
                    put.addHeader( "Content-Disposition", "attachment; filename=\"" + fn + "\"" );
                    put.addHeader( "Content-Type", mimeTypes.getContentType(dsFile) );
                    put.setEntity( new FileEntity(dsFile) );
                    int status = execute(put, true);
                    if ( status == 201 ) {
                        System.out.println("File: " + dsPath);
                        filesCreated++;
                    } else {
                        System.out.println("File: " + dsPath + ": " + status);
                        errors++;
                    }
                }
            }
        } catch ( Exception ex ) {
            errors++;
            System.out.println("Error: " + ex.toString());
        }
    }

    private static void loadMetadata( String objid, File metaFile, boolean merge ) {
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
                if ( linkPath.equals(repositoryURL) ) {
                    System.err.println("XXX: " + e.asXML());
                }
                if ( linkPath.startsWith(repositoryURL) ) {
                    linkPath = fixLink(linkPath, repositoryURL);
                    about.setValue(repositoryURL + linkPath);
                    createObject(linkPath);
                }
            }

            HttpPut put = new HttpPut(repositoryURL + objPath(objid));
            put.addHeader( "Content-Type", "application/rdf+xml" );
            if ( merge ) {
                // load RDF from repo
                System.out.println( objid + ": merging metadata");
                HttpGet get = new HttpGet( repositoryURL + objPath(objid) );
                get.addHeader( "Accept", "application/rdf+xml" );
                HttpResponse resp = client.execute(get);
                Model m = ModelFactory.createDefaultModel();
                m.read( resp.getEntity().getContent(), null, "RDF/XML" );

                // merge local updates
                m.read(new ByteArrayInputStream(doc.asXML().getBytes("utf-8")), null, "RDF/XML");
                final StringWriter sw = new StringWriter();
                m.write(sw);
                put.setEntity( new ByteArrayEntity(sw.toString().getBytes("utf-8")) );
            } else {
                // just use local RDF with received="minimal" header
                put.addHeader( "Prefer", "handling=lenient; received=\"minimal\"" );
                put.setEntity( new ByteArrayEntity(doc.asXML().getBytes("utf-8")) );
            }

            // update metadata
            int status = execute(put, true);
            if ( status == 204 ) {
                recordsUpdated++;
            } else {
                System.out.println("meta: " + status);
                errors++;
            }
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

    private static boolean exists( String path ) {
        HttpHead head = new HttpHead(repositoryURL + path);
        int status = execute(head, false);
        if ( status == 200 ) {
            return true;
        } else {
            return false;
        }
    }

    private static int execute( HttpRequestBase request, boolean verbose ) {
        try {
            if ( request.getURI().toString().indexOf("/:/") != -1 ) {
                throw new Exception("Invalid URL: " + request.getURI());
            }
            HttpResponse response = client.execute(request);
            int status = response.getStatusLine().getStatusCode();
            if ( verbose && status > 399 ) {
                System.out.println("URL..: " + request.getURI());
                System.out.println("Error: " + response.getStatusLine());
                if ( response.getEntity() != null ) {
                    String body = EntityUtils.toString(response.getEntity());
                    if ( body != null ) {
                        System.out.println(body);
                    }
                }
            }
            return status;
        } catch ( Exception ex ) {
            System.out.println("Error: " + ex.toString());
            return -1;
        } finally {
            request.releaseConnection();
        }
    }

    /**
     * Convert a filename to a file id.
    **/
    private static String fileId( final String objid, final String filename ) {
        String[] parts = filename.split("-",4);
        if ( parts[2].equals("0") ) {
            return parts[3];
        } else {
            return parts[2] + "/" + parts[3];
        }
    }

    public static String objPath( String s ) {
        return "/" + pairPath(s);
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
