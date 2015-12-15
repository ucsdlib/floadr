package edu.ucsd.library.floadr;

import static edu.ucsd.library.floadr.Floadr.pairPath;
import static java.lang.Integer.MAX_VALUE;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.jena.riot.WebContent.contentTypeSPARQLUpdate;
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
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
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
import org.fcrepo.client.FedoraResource;
import org.fcrepo.client.ReadOnlyException;
import org.fcrepo.client.impl.FedoraRepositoryImpl;
import org.slf4j.Logger;

import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.RDFWriter;
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
    private static String ldpDirectContainerFilePath = "/files";

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
        boolean ingestFailed = false;
        final List<String> errorIds = new ArrayList<>();
        final BufferedReader objectIdReader = new BufferedReader( new FileReader(objectIds) );
        for ( String id = null; (id = objectIdReader.readLine()) != null; ) {
            record++;
            ingestFailed = false;
            log.info(record + ": loading: " + id);
            final String objPath = "/" + Floadr.objPath(id);
            final String objURL = repositoryURL + objPath;
            final File metaFile = new File( sourceDir + "/" + pairPath(id) + "/"
                    + "20775-" + id + "-0-rdf.xml" );

            Document doc = null;
            // transform metadata
            final List<String> files = new ArrayList<>();
            final List<String> objects = new ArrayList<>();
            final Map<String, DAMSNode> damsNodes = new TreeMap<>();
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

                final List links = doc.selectNodes("//*[@rdf:resource]|//*[@rdf:about]");
                int linked = 0;
                for ( final Iterator it = links.iterator(); it.hasNext(); ) {
                    linked++;
                    final Element e = (Element)it.next();
                    final Attribute about = e.attribute(0);
                    String linkPath = about.getValue();

                    if ( linkPath.startsWith(repositoryURL) && !linkPath.endsWith("/fcr:content")) {
                        linkPath = fixLink(linkPath, repositoryURL);
                        about.setValue(repositoryURL + linkPath);

                        if ( e.getName().endsWith( DAMSNode.NODETYPE_FILE ) ) {
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

                }

                // separate components as top level objects and retain the component structure
                DAMSNode topDAMSNode = new DAMSNode(objURL, objURL);
                damsNodes.put(objURL, topDAMSNode);

                List<Node> components = doc.selectNodes("/rdf:RDF/*[local-name() = 'Object']/pcdm:hasMember/*[local-name() = 'Component']");
                for ( Node component : components ) {
            		String compID = component.selectSingleNode("@rdf:about").getStringValue();
            		DAMSNode compNode = new DAMSNode(compID, compID);
            		compNode.setNodeType(DAMSNode.NODETYPE_COMPONENT);
            		topDAMSNode.addChild(compNode);

            		// create separate top-level objects for components
                	toTopLevelObject ( repo, doc, component, objects, compNode, damsNodes );
                }

                // use LDP DirectContainer to contain object files
                List<Node> oNodes = doc.selectNodes("/rdf:RDF/*");
                for (Node oNode : oNodes) {
                	applyLdpDirectContainerForFiles( oNode );
                }

                // add the main object to the objects list
                objects.add(objURL);

                for (Node fileNode : fileNodes) {
                	files.add(fileNode.getStringValue());
                }

                dur2 = System.currentTimeMillis();
                linkDur += (dur2 - dur1);

                dur1 = System.currentTimeMillis();

                if ( !repo.exists(objPath) ) {
                	repo.createObject(objPath);
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

                DAMSNode[] objNodes = new DAMSNode[objects.size()];
                final ResIterator rit = m.listSubjects();

                while(rit.hasNext()) {
                    final String sid = rit.next().getURI();
                    final Model sm = describeSubject(sid, m);

                    log.debug(id + " Updating subject path " + sid + ": " + sm.size() );
                    if(sm.size() > 0) {
                        DAMSNode rdfNode = damsNodes.get( sid );
                        if (rdfNode == null) {
                            rdfNode = new DAMSNode ( sid, new ArrayList<DAMSNode>(), sm );
                            if ( files.indexOf( sid ) >= 0 )
                            	rdfNode.setNodeType(DAMSNode.NODETYPE_FILE);

                            damsNodes.put( sid, rdfNode );
                        } else {
                        	Model rdf = rdfNode.getModel();
                        	if (rdf == null || rdf.size() == 0)
                        		rdfNode.setModel(sm);
                        }

                        // identify top level object nodes for ingest
                        int oidx = objects.indexOf(sid);
                        if ( oidx >= 0 ) {
                            objNodes[oidx] = rdfNode;
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
                    String subPath = nid.replace(repositoryURL, "");
                    if (damsNode.getModel() == null) {

                        log.info("Missing RDF/XML: " + nid + "; path => " + subPath);
                        if ( !repo.exists( subPath ) ) {
                            repo.createObject( subPath );
                            damsNode.setVisited( true );
                            if ( subjectsMissing.indexOf(nid) < 0 ) {
                                subjectsMissing.add( nid );
                            }
                        }
                    } else {
                        // add blankNode to the parent subject model
                        if(subPath.indexOf("#") > 0) {
                            log.info("BlankNode RDF/XML: " + nid + "; path => " + subPath + "; ");
                            subPath = subPath.substring(0, subPath.indexOf("#"));
                            damsNodes.get(repositoryURL + subPath).model.add(damsNode.getModel());
                            damsNode.setVisited(true);
                        }
                     }
                }

                // ingest the nodes in order basing on dependency
                for (DAMSNode objNode : Arrays.asList(objNodes)) {
                	final String oPath = objNode.getNodeID().replace(repositoryURL, "");
                	if ( !repo.exists(oPath) )
                			repo.createObject(oPath);

                    do {
                    final List<DAMSNode> res = new ArrayList<>();
                    visitNode( objNode, res );
                    if ( res.size() > 0 ) {
                        final DAMSNode vNode = res.get(0);
                        if ( !vNode.visited ) {

                            final String sid = vNode.nodeID;
                            final String path = sid.replace(repositoryURL, "");

                            log.info( "Ingesting subject " + path + " in object " + objNode.getAlternativeID());
                            if ( !repo.exists(path) || objects.indexOf(sid) >= 0 || subjectsMissing.indexOf( vNode.getNodeID() ) >= 0 ) {

                            	try {
                                // create the filestream
                                if ( vNode.getNodeType().equals(DAMSNode.NODETYPE_FILE) ) {
                                    log.info( sid + ": datastream " + path );

                                    ingestFile(httpClient, repo, objNode.getAlternativeID().replace(repositoryURL, ""), sid.replace(repositoryURL, ""), sourceDir.getAbsolutePath());
                                }

                                // create the subject and make it indexable
                                if ( !vNode.getNodeType().equals(DAMSNode.NODETYPE_FILE) && !sid.endsWith("/fcr:content") ) {
                                    makeIndexable( repo.findOrCreateObject(path) );
                                }
                                // update subject properties
                                updateSubject( httpClient, vNode.model, repositoryURL, path + (vNode.getNodeType().equals(DAMSNode.NODETYPE_FILE) ? "/fcr:metadata" : ""), "application/rdf+xml" );
                                // handling federates files linking
                                if ( StringUtils.isNoneBlank( federatedURL ) && (sid.endsWith("/fcr:content") || (files.indexOf( sid ) >= 0 && !damsNodes.containsKey(sid + "/fcr:content"))) ) {
                                    final String dsPath = path.replace("/fcr:content", "");
                                    final String fedURL = federatedURL + path;
                                    log.debug( sid + " updating federated datastream " + fedURL +  " for " + path );
                                    final String sparql = "insert data { <> <http://fedora.info/definitions/v4/rels-ext#hasExternalContent> <" + fedURL + "> }";
                                    final FedoraDatastream ds = repo.findOrCreateDatastream( dsPath );
                                    ds.updateProperties( sparql );;
                                }
                            	} catch (Exception e) {
                            		log.warn("Ingest object " + objPath + " faild in " + sid, e);

                            		ingestFailed = true;
                                    errors++;
                            		if( !errorIds.contains(id) )
                                        errorIds.add(id);
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

                // link components to object with ore:Proxy
                if (objects.size() > 1) {
                    linkComponentsToObject( httpClient, repo, topDAMSNode, Arrays.asList(objNodes) );
                }

                dur2 = System.currentTimeMillis();
                metaDur += (dur2 - dur1);
                success++;
            } catch ( final Exception ex ) {
                log.warn("Error updating " + objPath + ": " + ex.toString() );
                ingestFailed = true;
                ex.printStackTrace();
                if ( doc != null ) { log.debug( doc.asXML() ); }
                errors++;
                if( !errorIds.contains(id) )
                    errorIds.add(id);
            }

            if ( ingestFailed && doc != null ) {
            	log.warn( doc.asXML() ); 
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

    private static void toTopLevelObject (FedoraRepository repo, Document doc, Node component, 
    		List<String> objects, DAMSNode parentDAMSNode, Map<String, DAMSNode> damsNodes) throws FedoraException {
    	// handling component as top level object
        Node subjectNode = component.selectSingleNode("@rdf:about");

        // replace the component ID with new ID
    	String nPath = repo.createResource("").getPath();
    	String nid = repo.getRepositoryUrl() + nPath;
        String cid = subjectNode.getStringValue();
        subjectNode.setText(nid);
        List<Node> nodes = component.selectNodes("*/*[@rdf:about]");
        for (Node node : nodes) {
        	Node resNode = node.selectSingleNode("@rdf:about");
        	resNode.setText(resNode.getStringValue().replace(cid, nid));
        }
        log.debug("Component " + cid + " => " + nid + " <=> " + subjectNode.getStringValue());

        parentDAMSNode.setNodeID(nid);
        // add each component object to the object ordered list and objects map
        objects.add(0, nid);
        damsNodes.put(nid, parentDAMSNode);

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
    		String compID = comp.selectSingleNode("@rdf:about").getStringValue();
    		DAMSNode childNode = new DAMSNode(compID, compID);
    		childNode.setNodeType(DAMSNode.NODETYPE_COMPONENT);
    		parentDAMSNode.addChild(childNode);
    		toTopLevelObject (repo, doc, comp, objects, childNode, damsNodes);
        }
    }

    private static void applyLdpDirectContainerForFiles (Node topNode) {
    	String oid = topNode.selectSingleNode("@rdf:about").getStringValue();
        List<Node> nodes = topNode.selectNodes("*[contains(local-name(), '" + DAMSNode.NODETYPE_FILE + "')]/*");
        for (Node node : nodes) {
    		Node resNode = node.selectSingleNode("@rdf:about");
    		resNode.setText(resNode.getStringValue().replace(oid, oid + ldpDirectContainerFilePath));
    		log.debug("Converted file " + resNode.getStringValue() + " to use LDP Direct Container: " + oid + ldpDirectContainerFilePath);
    	}
    }

    private static void linkComponentsToObject(HttpClient httpClient, FedoraRepository repo, DAMSNode parent, List<DAMSNode> objects) throws Exception {
    	List<DAMSNode> components = new ArrayList<>();
    	for (DAMSNode comp : parent.getChilden()) {
    		if (objects.indexOf(comp) >= 0)
    			components.add(comp);
    	}
	    if (components.size() > 0) {
	    	for (DAMSNode component : components) {
	    		// create members container
	    		String membersPath = parent.getNodeID().replace(repo.getRepositoryUrl(), "") + "/members";
	    		createMembers( httpClient, repo, membersPath );
	    		// create ore:Proxy
	    		createProxy( httpClient, repo, membersPath, parent, component );
	    	}
    	}
    }

    private static void makeIndexable( final FedoraObject obj ) throws FedoraException {
        obj.updateProperties("insert { <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://fedora.info/definitions/v4/indexing#indexable> } where {}");
    }

    private static void createMembers( final HttpClient httpClient, final FedoraRepository repo, final String membersPath ) throws Exception {
        int idx = repo.getRepositoryUrl().indexOf("/", 9);
        String root = repo.getRepositoryUrl().substring(idx);
        String rdfTurtle = "@prefix ldp: <http://www.w3.org/ns/ldp#>"
        		+ " @prefix pcdm: <http://pcdm.org/models#>"
        		+ " @prefix ore: <http://www.openarchives.org/ore/terms/> "
        		+ " <> a ldp:IndirectContainer ;"
        		+ " ldp:membershipResource <" + root+ membersPath.substring(0, membersPath.lastIndexOf("/")) + "> ;"
        		+ " ldp:hasMemberRelation pcdm:hasMember ;"
        		+ " ldp:insertedContentRelation ore:proxyFor .";
        updateSubject( httpClient, rdfTurtle, repo.getRepositoryUrl(), membersPath, "text/turtle");
    }

    private static void createProxy( final HttpClient httpClient, final FedoraRepository repo, String membersPath, DAMSNode parent, DAMSNode component ) throws Exception {
        int idx = repo.getRepositoryUrl().indexOf("/", 9);
        String root = repo.getRepositoryUrl().substring(idx);
        String cid = component.getNodeID();
        String proxyPath = membersPath + cid.substring(cid.lastIndexOf("/"));
        String rdfTurtle = "@prefix ore: <http://www.openarchives.org/ore/terms/> "
        		+ " <> ore:proxyFor <" + root + component.getNodeID().replace(repo.getRepositoryUrl(), "") + "> ;"
        		+ " ore:proxyIn <" + root + parent.getNodeID().replace(repo.getRepositoryUrl(), "") + "> .";
    	updateSubject( httpClient, rdfTurtle, repo.getRepositoryUrl(), proxyPath, "text/turtle");
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
    private static void updateSubject(final HttpClient httpClient, final Model m, final String repositoryURL, final String path, String format) throws Exception {
    	final StringWriter sw = new StringWriter();
    	try {
    		// serialize RDF
    		RDFWriter rdfw = m.getWriter("RDF/XML-ABBREV");
    		rdfw.setProperty("prettyTypes", new Resource[]{});

    		rdfw.write( m, sw, null );
            updateSubject( httpClient, sw.toString(), repositoryURL, path, format);
        } catch (final Exception e) {
            throw e;
        } finally {
            if ( sw != null ) {
                sw.close();;
            }
        }
    }
    private static void updateSubject(final HttpClient httpClient, final String content, final String repositoryURL, final String path, String format) throws Exception {
        HttpPut put = null;
        String update = content;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "handling=lenient; received=minimal");
        try {
            log.debug(update);
            put = createTriplesPutMethod(repositoryURL, path, new ByteArrayInputStream(update.getBytes("utf-8")),
                    format, headers);
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
    private static void sparqlUpdate(final HttpClient httpClient, final String content, final String repositoryURL, final String path) throws Exception {
        HttpPatch patch = null;
        try {
            log.debug(content);
            patch = createPatchMethod(repositoryURL, path, content);
            final HttpResponse response= httpClient.execute(patch);
            final int status = response.getStatusLine().getStatusCode();

            if ( status == 204 ) {
                log.debug("Updated subject: " + path);
            } else if ( status == 201 ) {
                log.debug("Created subject: " + path);
            } else {
                log.warn(content);
                throw new FedoraException("Failed " + path + "; status " + status + ".\n");
            }
        } catch (final Exception e) {
            throw e;
        } finally {
            if ( patch != null ) {
                patch.releaseConnection();
            }
        }

    }
    private static void ingestFile(HttpClient httpClient, FedoraRepository repo, String objPath, String dsPath, String sourceDir)
    		throws Exception {
    	final String[] paths = dsPath.substring(dsPath.lastIndexOf(ldpDirectContainerFilePath) + ldpDirectContainerFilePath.length()).split("/");
    	String mappedFilePath = objPath;
    	if (dsPath.startsWith(objPath)) {
    		// simple objects that keep its original ark with cid 0
    		mappedFilePath += "/0";
    	}
    		
    	for (int i=1; i < paths.length; i++) {
    		mappedFilePath += "/" + paths[i];
    	}
    	final String fileName = getFileNameFromPath(mappedFilePath);
    	int idx = fileName.indexOf("-");
        final File objDir = new File( sourceDir, Floadr.pairPath(fileName.substring(idx + 1, idx + 11)) );
        final File srcFile = new File (objDir, fileName);
    	log.info("Ingesting file " + dsPath + " for object " + objPath + ": " + srcFile.getAbsolutePath());

    	if ( srcFile.exists() ) {
    		// create LDP direct container to contain files
    		int cidx = dsPath.lastIndexOf(ldpDirectContainerFilePath);
    		if (cidx < 0)
    			log.error("Need to use LDP DirectContainer to contain file: " + dsPath);

    		String containerPath = dsPath.substring(0, cidx + ldpDirectContainerFilePath.length());
    		findOrCreateDirectContainer( httpClient, repo, containerPath );

    		// ingest the file
            loadFile( repo, dsPath, srcFile );

            log.debug("Ingested file:" + dsPath);
            // update the RDF metadata of the ldp:NonRdfSource to specify that the resource is a pcdm:File
            String sparqlUpdate = "PREFIX pcdm: <http://pcdm.org/models#>"
            		+ " INSERT { <> a pcdm:File } WHERE {}";
            sparqlUpdate( httpClient, sparqlUpdate, repo.getRepositoryUrl(), dsPath + "/fcr:metadata");
    	}
    }

    private static void findOrCreateDirectContainer( final HttpClient httpClient, final FedoraRepository repo, final String directContainerPath ) throws Exception {
		//create LDP DirectContainer to contain files if doesn't exist
        final String objPath = directContainerPath.substring(0, directContainerPath.lastIndexOf(ldpDirectContainerFilePath));
		final String containerPath = directContainerPath + "/";
		
		if (!repo.exists(containerPath)) {
			int idx = repo.getRepositoryUrl().indexOf("/", 9);
	        String root = repo.getRepositoryUrl().substring(idx);
	        String rdfTurtle = "@prefix ldp: <http://www.w3.org/ns/ldp#> "
	        		+ " @prefix pcdm: <http://pcdm.org/models#> "
	        		+ " <> a ldp:DirectContainer ;"
	        		+ " ldp:membershipResource <" + root + objPath + "> ;"
	        		+ " ldp:hasMemberRelation pcdm:hasFile .";
	    	updateSubject( httpClient, rdfTurtle, repo.getRepositoryUrl(), containerPath, "text/turtle");
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
     * Create a request to update triples with SPARQL Update.
     * @param path The datastream path.
     * @param sparqlUpdate SPARQL Update command.
     * @return created patch based on parameters
     * @throws FedoraException
    **/
    public static HttpPatch createPatchMethod(final String repositoryURL, final String path, final String sparqlUpdate) throws FedoraException {
        if ( isBlank(sparqlUpdate) ) {
            throw new FedoraException("SPARQL Update command must not be blank");
        }

        final HttpPatch patch = new HttpPatch(repositoryURL + path);
        patch.setEntity( new ByteArrayEntity(sparqlUpdate.getBytes()) );
        patch.setHeader("Content-Type", contentTypeSPARQLUpdate);
        return patch;
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
