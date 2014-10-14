package edu.ucsd.library.floadr;

import static org.slf4j.LoggerFactory.getLogger;
import static edu.ucsd.library.floadr.Floadr.pairPath;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
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

import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.ModelFactory;

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
        String federatedURL = null;
        if ( args.length > 3 ) {
            federatedURL = args[3];
        }

        // load goldilocks.xsl
        final StreamSource xsl = new StreamSource(Gloadr.class.getClassLoader().getResourceAsStream(
                "xsl/goldilocks.xsl"));
        final Transformer xslt = TransformerFactory.newInstance().newTransformer(xsl);
        xslt.setParameter("repositoryURL", repositoryURL);

        // create repository object
        final FedoraRepository repo = new FedoraRepositoryImpl(repositoryURL);

        // profiling data
        long dur1;
        long dur2;
        long xsltDur = 0L;
        long recsDur = 0L;
        long linkDur = 0L;
        long metaDur = 0L;

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
            final File metaFile = new File( sourceDir + "/" + pairPath(id)
                    + "20775-" + id + "-0-rdf.xml" );

            Document doc = null;
            // transform metadata
            try {
                dur1 = System.currentTimeMillis();
                final DocumentResult result = new DocumentResult();
                xslt.transform( new StreamSource(metaFile), result );
                doc = result.getDocument();
                dur2 = System.currentTimeMillis();
                xsltDur += (dur2 - dur1);

                // make sure links work
            	dur1 = System.currentTimeMillis();
                final List links = doc.selectNodes("//*[@rdf:resource]|//*[@rdf:about]");
                int linked = 0;
                for ( Iterator it = links.iterator(); it.hasNext(); ) {
                    linked++;
                    Element e = (Element)it.next();
                    Attribute about = e.attribute(0);
                    String linkPath = about.getValue();
                    if ( linkPath.startsWith(repositoryURL) && !linkPath.endsWith("/fcr:content")) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);
                        if ( !repo.exists(linkPath) ) {
                            // only create/update records if they don't already exist
                            if ( e.getName().equals("File") ) {
                                log.info(record + ": datastream " + linkPath + " (" + linked + ")");
                                repo.createDatastream( linkPath, new FedoraContent().setContent(new ByteArrayInputStream(new byte[]{})) );
                            } else {
                                log.info(record + ": creating " + linkPath + " (" + linked + ")");
                                repo.createObject( linkPath );
                            }
                        }
                    } else if ( linkPath.startsWith(repositoryURL)
                            && linkPath.endsWith("/fcr:content") && federatedURL != null ) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);
                        final String fedURL = federatedURL + linkPath;
                        final String mimeType = e.valueOf("hasContent/binary/mimeType");
                        final FedoraDatastream ds = repo.findOrCreateDatastream(linkPath.replaceAll(repositoryURL,""));
                        String sparql = "insert data { <> <http://fedora.info/definitions/v4/rels-ext#hasExternalContent> <" + fedURL + "> }";
                        ds.updateProperties(sparql);
                    } else {
                        System.out.println("skipping: " + linkPath );
                    }
                }
            	dur2 = System.currentTimeMillis();
            	linkDur += (dur2 - dur1);

                // make sure object and rights nodes exist
            	dur1 = System.currentTimeMillis();
                if ( !repo.exists(objPath + "rights") ) {
                    log.info(record + ": creating " + objPath + "rights");
                    repo.createObject(objPath + "rights");
                }
                log.info(record + ": loading " + objPath);
                FedoraObject obj = repo.getObject(objPath);
            	dur2 = System.currentTimeMillis();
            	recsDur += (dur2 - dur1);

                // update metadata
                log.info(record + ": updating " + objPath);
            	dur1 = System.currentTimeMillis();
				// load existing graph into a model
                Model m = ModelFactory.createDefaultModel();
                for ( Iterator<Triple> it = obj.getProperties(); it.hasNext(); ) {
                    final Statement s = m.asStatement(it.next());
                    m.add( s );
                }
                m.write(System.out);

                // update model with our rdf
                m.read(new ByteArrayInputStream(doc.asXML().getBytes("utf-8")), null, "RDF/XML");

                // serialize updated model and update repo
                final StringWriter sw = new StringWriter();
                m.write(sw);
                final String update = sw.toString();
                System.out.println("\n----------\n" + update + "\n----------");
                obj.updateProperties(new ByteArrayInputStream(update.getBytes("utf-8")), "application/rdf+xml");
            	dur2 = System.currentTimeMillis();
            	metaDur += (dur2 - dur1);
                success++;
            } catch ( Exception ex ) {
                log.warn("Error updating " + objPath + ": " + ex.toString());
                if ( doc != null ) { log.warn( doc.asXML() ); }
                errors++;
                errorIds.add(id);
            }
        }

        log.info("done: " + success + "/" + errors);
        for ( final String id : errorIds ) {
            log.info("error: " + id);
        }
        log.info("xslt: " + xsltDur + ", recs: " + recsDur + ", link: " + linkDur + ", meta: " + metaDur);
    }
	private static String fixLink( String path, String repositoryURL ) {
        String s = path.replaceAll(repositoryURL + "/", "");
        String[] parts = s.split("/",2);
        parts[0] = pairPath(parts[0]);
        String newPath = "/" + parts[0];
        if ( parts.length == 2 ) {
            newPath += parts[1];
        }
        log.debug("fixLink: " + path + " => " + newPath);
        return newPath;
    }
}
