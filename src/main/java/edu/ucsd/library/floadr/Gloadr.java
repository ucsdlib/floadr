package edu.ucsd.library.floadr;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

import org.fcrepo.client.FedoraContent;
import org.fcrepo.client.FedoraDatastream;
import org.fcrepo.client.FedoraException;
import org.fcrepo.client.FedoraRepository;
import org.fcrepo.client.FedoraObject;
import org.fcrepo.client.impl.FedoraRepositoryImpl;

import org.slf4j.Logger;

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
        int success = 0;
        int errors  = 0;
        int record = 0;
        final List<String> errorIds = new ArrayList<>();
        BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            record++;
            log.info(record + ": loading: " + id);
            final String objPath = "/" + Floadr.objPath(id);
            final File metaFile = new File( sourceDir + "/" + Floadr.pairPath(id)
                    + "20775-" + id + "-0-rdf.xml" );

            // transform metadata
            final DocumentResult result = new DocumentResult();
            xslt.transform( new StreamSource(metaFile), result );
            final Document doc = result.getDocument();

            try {
                // make sure rights node exists
                if ( !repo.exists( objPath + "rights") ) {
                    log.info(record + ": creating " + objPath + "rights");
                    repo.createObject(objPath + "rights");
                }

                // make sure links work
                final List links = doc.selectNodes("//*[@rdf:resource]|//*[@rdf:about]");
                int linked = 0;
                Map<FedoraObject,Node> metadata = new HashMap<>();
                metadata.put( repo.getObject(objPath), doc );
                for ( Iterator it = links.iterator(); it.hasNext(); ) {
                    linked++;
                    Element e = (Element)it.next();
                    Attribute about = e.attribute(0);
                    String linkPath = about.getValue();
                    if ( linkPath.startsWith(repositoryURL) && !linkPath.endsWith("/fcr:content")) {
                        linkPath = linkPath.replaceAll(repositoryURL,"");
                        if ( linkPath.equals(objPath) ) {
                            // current record's metadata
                        } else if ( !repo.exists(linkPath) ) {
                            // only create/update records if they don't already exist
                            log.info(record + ": creating " + linkPath + " (" + linked + ")");
                            final FedoraObject obj = repo.createObject( linkPath );
                            if ( about.getName().equals("about") && !linkPath.startsWith(objPath)) {
                                // only post metadata about external objs that don't already exist
                                //metadata.put(obj, e);
                            }
                        }
                    }
                }

                // update metadata
                int updated = 0;
                for ( FedoraObject obj : metadata.keySet() ) {
                    updated++;
                    final Node node = metadata.get(obj);
                    log.info(record + ": updating " + obj.getPath() + " (" + updated + ")");
                    obj.updateProperties( new ByteArrayInputStream(node.asXML().getBytes()),
                            "application/rdf+xml");
                }
                success++;
            } catch ( Exception ex ) {
                log.warn("Error updating " + objPath + ": " + ex.toString());
                log.warn( doc.asXML() );
                errors++;
                errorIds.add(id);
            }
        }

        log.info("done: " + success + "/" + errors);
        for ( final String id : errorIds ) {
            log.info("error: " + id);
        }
    }
}
