package edu.ucsd.library.floadr;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

import javax.activation.MimetypesFileTypeMap;

import org.fcrepo.client.FedoraContent;
import org.fcrepo.client.FedoraDatastream;
import org.fcrepo.client.FedoraException;
import org.fcrepo.client.FedoraRepository;
import org.fcrepo.client.FedoraObject;
import org.fcrepo.client.impl.FedoraRepositoryImpl;

import org.slf4j.Logger;

/**
 * Fedora loader utility.
 * @author escowles
 * @since 2014-08-27
**/
public class Floadr {

    private static Logger log = getLogger(Floadr.class);
    private static MimetypesFileTypeMap mimeTypes = new MimetypesFileTypeMap();
    private static int errors = 0;
    private static int created = 0;
    private static int skipped = 0;

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: Repository baseURL,
     *     1: File listing object IDs (one per line),
     *     2: Base directory to find source files.
    **/
    public static void main( String[] args ) throws FedoraException, IOException {
        final String repositoryURL = args[0];
        final File objectIds = new File(args[1]);
        final File sourceDir = new File(args[2]);

        // create repository object
        final FedoraRepository repo = new FedoraRepositoryImpl(repositoryURL);

        // for each id, find/create a fedora object and then load each file
        BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            final String pairPath = pairPath(id);
            final File objDir = new File( sourceDir, pairPath );
            final File[] objFiles = objDir.listFiles();
            if ( objFiles.length > 0 ) {
                final FedoraObject obj = repo.findOrCreateObject( "/" + objPath(id) );
                log.info("Object: " + obj.getPath());
                for ( File f : objFiles ) {
                    loadFile( repo, id, f );
                }
            }
        }
        log.info("created: " + created + ", skipped: " + skipped + ", errors: " + errors);
    }

    private static void loadFile( FedoraRepository repo, String objid, File dsFile ) {
        try {
            final String fn = dsFile.getName();
            if ( fn.matches("^20775-" + objid + "-\\d-.*") && !fn.endsWith("rdf.xml") ) {
                final String fileId = fileId( objid, fn );
                if ( fileId.indexOf("/") != -1 ) {
                    final String compPath = "/" + objPath(objid) + "/" + fileId.replaceAll("/.*","");
                    repo.findOrCreateObject( compPath );
                }
                final String dsPath = "/" + objPath(objid) + "/" + fileId;
                if ( repo.exists( dsPath ) ) {
                    log.info("  Skipped: " + fn);
                    skipped++;
                } else {
                    final InputStream in = new FileInputStream(dsFile);
                    final String mimeType = mimeTypes.getContentType(dsFile);
                    final FedoraContent content = new FedoraContent().setContent(in)
                            .setFilename(fn).setContentType(mimeType);
                    final FedoraDatastream ds = repo.createDatastream( dsPath, content );
                    log.info("  Datastream: " + ds.getPath());
                    created++;
                }
            }
        } catch ( Exception ex ) {
            errors++;
            log.warn("Error: " + ex.toString());
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
        return pairPath(s);
    }

    /**
     * Convert a string into a pairpath directory tree.
    **/
    public static String pairPath( String s ) {
        if ( s == null ) { return null; }
        String result = "";
        int i = 0;
        while( i < (s.length() - 1) && i < 10)
        {
            result += s.substring(i,i+2);
            result += "/";
            i += 2;
        }
        if ( s.length() > i )
        {
            result += s.substring(i) + "/";
        }
        return result.substring(0,  result.length() - 1);
    }
}
