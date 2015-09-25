package edu.ucsd.library.floadr;

import static edu.ucsd.library.floadr.Floadr.pairPath;
import static java.lang.Integer.MAX_VALUE;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.DocumentResult;
import org.fcrepo.client.FedoraContent;
import org.fcrepo.client.FedoraDatastream;
import org.fcrepo.client.FedoraException;
import org.fcrepo.client.FedoraObject;
import org.fcrepo.client.FedoraRepository;
import org.fcrepo.client.ReadOnlyException;
import org.fcrepo.client.impl.FedoraRepositoryImpl;
import org.slf4j.Logger;

import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;

/**
 * Fedora metadata loader utility.
 * @author lsitu
 * @author escowles
 * @since 2014-09-05
 **/
public class Gloadr {

    private static Logger log = getLogger(Gloadr.class);
    private static List<String> subjectsMissing = new ArrayList<>();

    /**
     * Command-line operation.
     * @param args Command-line arguments: 0: Repository baseURL,
     *     1: File listing object IDs (one per line),
     *     2: Base directory to find source files.
     **/
    public static void main( final String[] args )
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

        final HttpClient httpClient = getHttpClient(repositoryURL, null, null);
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
        final BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            record++;
            log.info(record + ": loading: " + id);
            final String objPath = "/" + Floadr.objPath(id);
            final File metaFile = new File( sourceDir + "/" + pairPath(id) + "/"
                    + "20775-" + id + "-0-rdf.xml" );

            Document doc = null;
            // transform metadata
            final List<String> files = new ArrayList<>();
            final List<String> objects = new ArrayList<>();
            List<Node> objectNodes = new ArrayList<>();
            List<Node> fileNodes = new ArrayList<Node>();

            final String update = null;
            try {
                dur1 = System.currentTimeMillis();
                final DocumentResult result = new DocumentResult();
                xslt.transform( new StreamSource(metaFile), result );
                doc = result.getDocument();
                dur2 = System.currentTimeMillis();
                xsltDur += (dur2 - dur1);

                // make sure links work
                dur1 = System.currentTimeMillis();

                List<Node> components = doc.selectNodes("/rdf:RDF/*[local-name() = 'Object']/pcdm:hasMember/*[local-name() = 'Component']");
                for ( Node component : components ) {
                	// create separate top-level objects for components
                	toTopLevelObject ( repo, id, doc, component, objectNodes ) ;
                }

                // add the converted url to objects list
                for ( Node objectNode : objectNodes ) {
                    objects.add(objectNode.getStringValue());
                }

                final List links = doc.selectNodes("//*[@rdf:resource]|//*[@rdf:about]");
                int linked = 0;
                for ( final Iterator it = links.iterator(); it.hasNext(); ) {
                    linked++;
                    final Element e = (Element)it.next();
                    final Attribute about = e.attribute(0);
                    String linkPath = about.getValue();

                    // new component object id that don't need fix
                    boolean isNewID = false;
                    if (linkPath.startsWith(repositoryURL)) {
                    	for (String object : objects) {
                    		if (linkPath.indexOf(object) >= 0) {
                    			isNewID = true;
                    			break;
                    		}
                    	}
                    }

                    if (!isNewID) {
                    if ( linkPath.startsWith(repositoryURL) && !linkPath.endsWith("/fcr:content")) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);

                        if ( e.getName().endsWith("File") ) {
                            log.debug("Added File: " + repositoryURL + linkPath);
                            fileNodes.add(about);
                            //files.add( repositoryURL + linkPath );
                        }

                    } else if ( linkPath.startsWith(repositoryURL)
                            && linkPath.endsWith("/fcr:content") && federatedURL != null ) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);

                    }  else if ( linkPath.indexOf("/ark:/20775/") > 0 ) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);
                    } else {
                        log.info("skipping: " + linkPath );
                    }
                    }else{
                    	// add file to file list
                        if ( e.getName().endsWith("File") ) {
                            log.debug("Added File: " + about.getStringValue());
                            fileNodes.add(about);
                        }
                    }

                    if ( linkPath.indexOf("#N") > 0 ) {
                        final Node node = about.getParent();
                        about.detach();
                        ((Element)node).addAttribute("rdf:nodeID", about.getStringValue());
                    }
                }

                // add the main object to the objects list
                Node objectNode = doc.selectSingleNode("/rdf:RDF/*[local-name() = 'Object']/@rdf:about");
                objectNodes.add(objectNode);
                objects.add(objectNode.getStringValue());

                for (Node fileNode : fileNodes) {
                	files.add(fileNode.getStringValue());
                }

                dur2 = System.currentTimeMillis();
                linkDur += (dur2 - dur1);

                // make sure object and rights nodes exist
                dur1 = System.currentTimeMillis();

                if ( !repo.exists(objPath) ) {
                	repo.createObject(objPath);
                    log.debug(record + ": creating " + objPath + "rights");
                    repo.createObject(objPath + "rights");
                }

                dur2 = System.currentTimeMillis();
                recsDur += (dur2 - dur1);

                final Map<String, Model> models = new TreeMap<>();

                dur1 = System.currentTimeMillis();

                //System.out.println("Transformed RDF: " + doc.asXML());
                final Model m = ModelFactory.createDefaultModel();
                // update model with our rdf
                m.read(new ByteArrayInputStream(doc.asXML().getBytes("utf-8")), null, "RDF/XML");

                final StringWriter sw = new StringWriter();
                m.write(sw);

                final Map<String, DAMSNode> damsNodes = new TreeMap<>();

                DAMSNode[] objNodes = new DAMSNode[objectNodes.size()];
                final ResIterator rit = m.listSubjects();

                while(rit.hasNext()) {
                    final String sid = rit.next().getURI();
                    final Model sm = describeSubject(sid, m);

                    log.debug(id + " Updating subject path " + sid + ": " + sm.size() );
                    if(sm.size() > 0) {
                        DAMSNode rdfNode = damsNodes.get( sid );
                        if (rdfNode == null) {
                            rdfNode = new DAMSNode ( sid, new ArrayList<DAMSNode>(), sm );
                            damsNodes.put( sid, rdfNode );

                            // identify top level object nodes for ingest
                            int oidx = objects.indexOf(sid);
                            if ( oidx >= 0 ) {
                                objNodes[oidx] = rdfNode;
                            }
                        }
                    }
                }

                // build the children list
                final DAMSNode[] damsNodesArr = damsNodes.values().toArray( new DAMSNode[damsNodes.size()] );
                for ( final DAMSNode damsNode : damsNodesArr ) {
                    final List<Resource> ress = getObjectResource( damsNode.model );
                    for ( final Resource res : ress ) {
                        final String nid = res.getURI();
                        if ( nid != null && nid.startsWith(repositoryURL) ) {
                            DAMSNode dn = damsNodes.get(nid);
                            if (dn == null) {
                                dn = new DAMSNode(nid);
                                damsNodes.put(nid, dn);
                            }
                            damsNode.addChild(dn);
                        }
                    }
                }

                // check for missing subjects and create it for now so that the ingest won't interrupted.
                for ( final DAMSNode damsNode : damsNodes.values() ) {
                    final String nid = damsNode.getNodeID();
                    if (damsNode.getModel() == null) {

                        final String subPath = nid.replace(repositoryURL, "");
                        log.info("Missing RDF/XML: " + nid + "; path => " + subPath);
                        if ( !repo.exists( subPath ) ) {
                            repo.createObject( subPath );
                            damsNode.setVisited( true );
                            if ( subjectsMissing.indexOf(nid) < 0 ) {
                                subjectsMissing.add( nid );
                            }
                        }
                    }
                }

                // ingest the nodes in order basing on dependency
                for (DAMSNode objNode : Arrays.asList(objNodes)) {
                	final String oPath = objNode.getNodeID().replace(repositoryURL, "");
                	if ( !repo.exists(oPath) )
                			repo.createObject(oPath);
                    if ( !repo.exists(oPath + "rights") ) {
                        log.debug(record + ": creating " + oPath + "rights");
                        repo.createObject(oPath + "rights");
                    }

                    do {
                    final List<DAMSNode> res = new ArrayList<>();
                    visitNode( objNode, res );
                    if ( res.size() > 0 ) {
                        final DAMSNode vNode = res.get(0);
                        if ( !vNode.visited ) {

                            final String sid = vNode.nodeID;
                            final String path = sid.replace(repositoryURL, "");

                            log.info( "Ingesting subject " + path );
                            if ( !repo.exists(path) || objects.indexOf(sid) >= 0 || subjectsMissing.indexOf( vNode.getNodeID() ) >= 0 ) {

                                // create the filestream
                                if ( files.indexOf( sid ) >= 0 ) {
                                    log.info( sid + ": datastream " + path );
                                    for (String object : objects) {
                                    	if (sid.indexOf(object) >= 0) {
                                    		
                                    	}
                                    }
                                    ingestFile(repo, objPath, sid.replace(repositoryURL, ""), sourceDir.getAbsolutePath());
                                }

                                // create the subject and make it indexable
                                if ( files.indexOf( sid ) < 0 && !sid.endsWith("/fcr:content") ) {
                                    makeIndexable( repo.findOrCreateObject(path) );
                                }
                                // update subject properties
                                updateSubject(httpClient, vNode.model, repositoryURL, path);

                                // handling federates files linking
                                if ( StringUtils.isNoneBlank( federatedURL ) && (sid.endsWith("/fcr:content") || (files.indexOf( sid ) >= 0 && !damsNodes.containsKey(sid + "/fcr:content"))) ) {
                                    final String dsPath = path.replace("/fcr:content", "");
                                    final String fedURL = federatedURL + path;
                                    log.debug( sid + " updating federated datastream " + fedURL +  " for " + path );
                                    final String sparql = "insert data { <> <http://fedora.info/definitions/v4/rels-ext#hasExternalContent> <" + fedURL + "> }";
                                    final FedoraDatastream ds = repo.findOrCreateDatastream( dsPath );
                                    ds.updateProperties( sparql );;
                                }

                                final int nidx = subjectsMissing.indexOf( vNode.getNodeID() );
                                if ( nidx >= 0 ) {
                                    subjectsMissing.remove( nidx );
                                }
                            }
                            vNode.setVisited(true);
                        }
                    }
                    } while ( !objNode.isVisited() );
                }

                dur2 = System.currentTimeMillis();
                metaDur += (dur2 - dur1);
                success++;
            } catch ( final Exception ex ) {
                log.warn("Error updating " + objPath + ": " + ex.toString() );
                ex.printStackTrace();
                if ( doc != null ) { log.warn( doc.asXML() ); }
                errors++;
                errorIds.add(id);
            }
        }

        log.info("done: " + success + "/" + errors);
        if ( subjectsMissing.size() > 0 ) {
            final StringBuilder sb = new StringBuilder();
            for (final String sub : subjectsMissing) {
                sb.append(sub + " \n");
            }
            log.info("The following subjects are created with no RDF/XML: \n" + sb.toString());

        }
        for ( final String id : errorIds ) {
            log.info("error: " + id);
        }
        log.info("xslt: " + xsltDur + ", recs: " + recsDur + ", link: " + linkDur + ", meta: " + metaDur);
    }

    private static List<Resource> getObjectResource( final Model m ) {
        final List<Resource> objs = new ArrayList<>();

        // iterate over the objects
        for (final NodeIterator nit = m.listObjects(); nit.hasNext(); ) {
            final RDFNode obj = nit.next();
            if (obj.isResource()) {
                objs.add( obj.asResource() );
            }
        }

        return objs;
    }

    private static void visitNode (final DAMSNode damsNode, final List<DAMSNode> result) {
        final List<DAMSNode> children = damsNode.getChilden();
        for ( final DAMSNode child : children ) {
            if ( !child.visited ) {
                if ( child.getChilden().size() == 0 ) {
                    result.add(child);
                    return;
                } else {
                    visitNode ( child, result );
                }
            }
        }
        result.add(damsNode);
    }

    private static void toTopLevelObject (FedoraRepository repo, String ark, Document doc, Node component, List<Node> objectNodes) throws FedoraException {
    	// handling component as top level object
        Node subjectNode = component.selectSingleNode("@rdf:about");
        objectNodes.add(0, subjectNode);

        // replace the component ID with new ID
    	String nid = repo.createResource("").getPath();
        String cid = subjectNode.getStringValue();
        subjectNode.setText(cid.replace("/" + ark, nid));
        List<Node> nodes = component.selectNodes("*/*[@rdf:about]");
        for (Node node : nodes) {
        	Node resNode = node.selectSingleNode("@rdf:about");
        	resNode.setText(resNode.getStringValue().replace("/" + ark, nid));
        }
        log.debug("Component " + cid + " => " + nid + " <=> " + subjectNode.getStringValue());

        // rename component to object
    	String path = component.getPath();
    	String elemName = path.substring(path.lastIndexOf("/") + 1);
    	component.setName(elemName.replace("Component", "Object"));
    	Node parent = component.getParent();
    	component.detach();
    	parent.detach();

    	// added as top level object
    	doc.getRootElement().add(component);

    	// loop through child components to make them to level objects as well
    	List<Node> components = component.selectNodes("pcdm:hasMember/*[local-name() = 'Component']");
    	for ( Node comp : components ) {
    		toTopLevelObject (repo, ark, doc, comp, objectNodes);
        	}
    }

    private static void makeIndexable( final FedoraObject obj ) throws FedoraException {
        obj.updateProperties("insert { <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://fedora.info/definitions/v4/indexing#indexable> } where {}");
    }

	private static String fixLink( final String path, final String repositoryURL ) {
        String s = null;
        if ( path.indexOf("/ark:/20775/") > 0 ) {
            s = path.substring(path.indexOf("/ark:/20775/") + 12, path.length());
        } else {
            s = path.replaceAll(repositoryURL + "/", "");
        }
        final String[] parts = s.split("/",2);
        parts[0] = pairPath(parts[0]);

        String newPath = "/" + parts[0];

        if ( parts.length == 2 ) {
            newPath += "/" + parts[1];
        }
        log.debug("fixLink: " + path + " => " + newPath);
        return newPath;
    }
    private static Model describeSubject(final String sid, final Model model) {
        final com.hp.hpl.jena.query.Query query = QueryFactory.create("DESCRIBE <" + sid + ">");
        return QueryExecutionFactory.create(query, model).execDescribe();
    }
    private static void updateSubject(final HttpClient httpClient, final Model m, final String repositoryURL, final String path) throws Exception {
        HttpPut put = null;
        String update = null;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "handling=lenient; received=minimal");
        try {

            final StringWriter sw = new StringWriter();
            m.write(sw);
            update = sw.toString();
            log.debug(update);
            put = createTriplesPutMethod(repositoryURL, path, new ByteArrayInputStream(update.getBytes("utf-8")),
                    "application/rdf+xml", headers);
            final HttpResponse response= httpClient.execute(put);
            final int status = response.getStatusLine().getStatusCode();

            if ( status == 204 ) {
                log.debug("Updated subject: " + path);
            } else if ( status == 201 ) {
                log.debug("Created subject: " + path);
            } else {
                log.warn(update);
                throw new FedoraException("Failed " + path + "; status " + status + ".\n");
            }
        } catch (final Exception e) {
            throw e;
        } finally {
            if ( put != null ) {
                put.releaseConnection();
            }
        }

    }

    private static void ingestFile(FedoraRepository repo, String objPath, String dsPath, String sourceDir) throws FedoraException {
    	final String[] paths = dsPath.split("/");
    	String mappedFilePath = objPath;
    	for (int i=6; i < paths.length; i++) {
    		mappedFilePath += "/" + paths[i];
    	}
    	final String fileName = getFileNameFromPath(mappedFilePath);
    	int idx = fileName.indexOf("-");
        final File objDir = new File( sourceDir, Floadr.pairPath(fileName.substring(idx + 1, idx + 11)) );
        final File srcFile = new File (objDir, fileName);
    	log.info("Ingesting file " + dsPath + " for object " + objPath + ": " + srcFile.getAbsolutePath());

    	if ( srcFile.exists() ) {
    		// ingest the file
            loadFile( repo, dsPath, srcFile );
        }
    }

    private static void loadFile( FedoraRepository repo, String dsPath, File dsFile ) {
        try {
            if ( repo.exists( dsPath ) ) {
                log.info("  Skipped: " + dsFile.getPath());
            } else {
                final InputStream in = new FileInputStream(dsFile);
                final String mimeType = Floadr.mimeTypes.getContentType(dsFile);
                final FedoraContent content = new FedoraContent().setContent(in)
                        .setFilename(dsFile.getName()).setContentType(mimeType);
                final FedoraDatastream ds = repo.createDatastream( dsPath, content );
                log.info("  Datastream: " + ds.getPath());
            }
        } catch ( Exception ex ) {
            log.warn("Error: " + ex.toString());
        }
    }

    private static String getFileNameFromPath(String path) {
    	String[] paths = path.split("/");
    	String fileID = "";
    	for (int i = 0; i < paths.length; i++ ) {
    		if (i <= 5)
    			fileID += StringUtils.isNotBlank(paths[i]) ? paths[i] : "";
    		else
    			fileID += "-" + paths[i];
    	}
    	return "20775-" + fileID ;
    }
    
    /**
     * Create a request to update triples.
     * @param path The datastream path.
     * @param updatedProperties InputStream containing RDF.
     * @param contentType Content type of the RDF in updatedProperties (e.g., "text/rdf+n3" or
     *        "application/rdf+xml").
     * @return PUT method
     * @throws FedoraException
     **/
    public static HttpPut createTriplesPutMethod(final String repositoryURL, final String path, final InputStream updatedProperties,
            final String contentType, final Map<String, String> headers) throws FedoraException {
        if ( updatedProperties == null ) {
            throw new FedoraException("updatedProperties must not be null");
        } else if ( isBlank(contentType) ) {
            throw new FedoraException("contentType must not be blank");
        }

        final HttpPut put = new HttpPut(repositoryURL + path);
        put.setHeader("Content-Type", contentType);
        if (headers != null) {
            for (final String header : headers.keySet()) {
                put.setHeader(header, headers.get(header));
            }
        }
        put.setEntity( new InputStreamEntity(updatedProperties) );
        return put;
    }

    /**
     * Execute a request for a subclass.
     *
     * @param request request to be executed
     * @return response containing response to request
     * @throws IOException
     * @throws ReadOnlyException
     **/
    public static HttpResponse execute( final HttpClient httpClient, final HttpUriRequest request ) throws IOException, ReadOnlyException {
        return httpClient.execute(request);
    }

    public static HttpClient getHttpClient(final String repositoryURL, final String username, final String password) {

        final PoolingClientConnectionManager connMann = new PoolingClientConnectionManager();
        connMann.setMaxTotal(MAX_VALUE);
        connMann.setDefaultMaxPerRoute(MAX_VALUE);

        final DefaultHttpClient httpClient = new DefaultHttpClient(connMann);
        httpClient.setRedirectStrategy(new DefaultRedirectStrategy());
        httpClient.setHttpRequestRetryHandler(new StandardHttpRequestRetryHandler(0, false));

        // If the Fedora instance requires authentication, set it up here
        if (!isBlank(username) && !isBlank(password)) {
            log.debug("Adding BASIC credentials to client for repo requests.");

            final URI fedoraUri = URI.create(repositoryURL);
            final CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(fedoraUri.getHost(), fedoraUri.getPort()),
                    new UsernamePasswordCredentials(username, password));

            httpClient.setCredentialsProvider(credsProvider);
        }

        return httpClient;
    }
}
