package edu.ucsd.library.floadr;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.fcrepo.client.FedoraContent;
import org.fcrepo.client.FedoraDatastream;
import org.fcrepo.client.FedoraException;
import org.fcrepo.client.FedoraRepository;
import org.fcrepo.client.FedoraObject;
import org.fcrepo.client.impl.FedoraRepositoryImpl;

/**
 * Fedora loader utility.
 * @author escowles
 * @since 2014-08-27
**/
public class Floadr
{
    public static void main( String[] args ) throws FedoraException
    {
        final String repositoryURL = args[0];
        final String objectID      = args[1];
        final FedoraRepository repo = new FedoraRepositoryImpl(repositoryURL);
        final FedoraObject obj = repo.findOrCreateObject( objectID );
        System.out.println( "Loaded: " + obj.getName() );

        final InputStream in = new ByteArrayInputStream("test content".getBytes());
        final FedoraContent content = new FedoraContent().setContent(in).setFilename("tmp.txt")
            .setContentType("text/plain");
        final FedoraDatastream ds = repo.createDatastream( objectID + "/content", content );
        System.out.println( "Created: " + ds.getPath() );
    }
}
