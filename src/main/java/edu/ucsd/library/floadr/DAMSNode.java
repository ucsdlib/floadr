package edu.ucsd.library.floadr;

import java.util.ArrayList;
import java.util.List;

import com.hp.hpl.jena.rdf.model.Model;

/**
 * Data structure for dams5 rdf/xml
 * @author lsitu
 * @date Since September 23, 2015
 */
public class DAMSNode {
	protected String nodeID = null;
	protected String alternativeID = null;
	protected List<DAMSNode> childen = new ArrayList<>();
	protected boolean visited = false;
	protected Model model = null;
	public DAMSNode (final String nodeID) {
		this(nodeID, new ArrayList<DAMSNode>());
	}

	public DAMSNode (final String nodeID, final String alternativeID) {
		this(nodeID, new ArrayList<DAMSNode>());
		this.alternativeID = alternativeID;
	}

	public DAMSNode (final String nodeID, final List<DAMSNode> childen) {
		this(nodeID, childen, null);
	}

	public DAMSNode (final String nodeID, final List<DAMSNode> childen, final Model model) {
		this.nodeID = nodeID;
		this.childen = childen;
		this.model = model;
	}

	public void addChild(final DAMSNode child){
		childen.add(child);
	}

	public String getNodeID() {
		return nodeID;
	}

	public void setNodeID(final String nodeID) {
		this.nodeID = nodeID;
	}

	public String getAlternativeID() {
		return alternativeID;
	}

	public void setAlternativeID(String alternativeID) {
		this.alternativeID = alternativeID;
	}

	public List<DAMSNode> getChilden() {
		return childen;
	}

	public boolean isVisited() {
		return visited;
	}

	public void setVisited(final boolean visited) {
		this.visited = visited;
	}

	public Model getModel() {
		return model;
	}

	public void setModel(final Model model) {
		this.model = model;
	}
}
