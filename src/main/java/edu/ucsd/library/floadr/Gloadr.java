package edu.ucsd.library.floadr;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stream.StreamResult;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.io.DocumentResult;

import org.fcrepo.client.FedoraContent;
import org.fcrepo.client.FedoraDatastream;
import org.fcrepo.client.FedoraException;
import org.fcrepo.client.FedoraRepository;
import org.fcrepo.client.FedoraObject;
import org.fcrepo.client.impl.FedoraRepositoryImpl;

import org.slf4j.Logger;

import org.w3c.dom.Node;

/**
 * Fedora metadata loader utility.
 * @author escowles
 * @since 2014-09-05
**/
public class Gloadr {

    private static Logger log = getLogger(Gloadr.class);

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: Repository baseURL,
     *     1: File listing object IDs (one per line),
     *     2: Base directory to find source files.
    **/
    public static void main( String[] args )
            throws FedoraException, IOException, TransformerException {
        final String repositoryURL = args[0];
        final File objectIds = new File(args[1]);
        final File sourceDir = new File(args[2]);

        // load goldilocks.xsl
        final StreamSource xsl = new StreamSource(Gloadr.class.getClassLoader().getResourceAsStream(
                "xsl/goldilocks.xsl"));
        final Transformer xslt = TransformerFactory.newInstance().newTransformer(xsl);
        xslt.setParameter("repositoryURL", repositoryURL);

        // create repository object
        final FedoraRepository repo = new FedoraRepositoryImpl(repositoryURL);

        // for each id, transform metadata and update fedora
        BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            final String pairPath = Floadr.pairPath(id);
            final String objPath = Floadr.objPath(id);
            final File objDir = new File( sourceDir, pairPath );
            final File metaFile = new File( objDir, "20775-" + id + "-0-rdf.xml" );

            // transform metadata
            final StreamSource metaSource = new StreamSource(metaFile);
            final DocumentResult result = new DocumentResult();
            xslt.transform( metaSource, result );
            final Document doc = result.getDocument();

            // XXX pairPath ids in doc....
            // XXX need to make sure files are there...

            // make sure links work
            final List elements = doc.selectNodes("//@rdf:resource");
            for ( Iterator it = elements.iterator(); it.hasNext(); ) {
                Attribute about = (Attribute)it.next();
                final String linkPath = about.getValue().replaceAll(repositoryURL,"");
                log.info( "link: " + about.getValue() );
                final FedoraObject obj = repo.findOrCreateObject( linkPath );
            }

            // make sure rights node exists
            final FedoraObject rights = repo.findOrCreateObject( "/" + objPath + "/rights" );

            // load metadata
            try {
                final FedoraObject obj = repo.findOrCreateObject( "/" + objPath );
                InputStream in = new ByteArrayInputStream(doc.asXML().getBytes());
                obj.updateProperties( in, "application/rdf+xml");
            } catch ( Exception ex ) {
                log.warn("Error updating " + objPath);
                log.warn( doc.asXML() );
            }
        }
    }
}
