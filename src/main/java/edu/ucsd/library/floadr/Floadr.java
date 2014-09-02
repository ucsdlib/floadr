package edu.ucsd.library.floadr;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

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
                final FedoraObject obj = repo.findOrCreateObject( "/" + pairPath );
                log.info("Object: " + obj.getPath());
                for ( File f : objFiles ) {
                    final String fn = f.getName();
                    final InputStream in = new FileInputStream(f);
                    final FedoraContent content = new FedoraContent().setContent(in)
                            .setFilename(fn); // XXX setContentType()
                    final String fileId = fileId( id, fn );
                    final String dsPath = "/" + pairPath + fileId;
                    final FedoraDatastream ds = repo.createDatastream( dsPath, content );
                    log.info("  Datastream: " + ds.getPath());
                }
            }
        }
    }

    /**
     * Convert a filename to a file id.
    **/
    private static String fileId( final String objid, final String filename ) {
        String fid = filename.substring(filename.indexOf(objid) + objid.length() + 1);
        fid = fid.replaceAll("^0-", "-");
        fid = fid.replaceAll("-", "/");
        return fid;
    }

    /**
     * Convert a string into a pairpath directory tree.
    **/
    private static String pairPath( String s ) {
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
