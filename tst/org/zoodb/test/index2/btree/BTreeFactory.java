package org.zoodb.test.index2.btree;

import org.zoodb.internal.server.index.btree.BTree;
import org.zoodb.internal.server.index.btree.BTreeBufferManager;
import org.zoodb.internal.server.index.btree.BTreeNode;
import org.zoodb.internal.server.index.btree.BTreeNodeFactory;
import org.zoodb.internal.server.index.btree.nonunique.NonUniquePagedBTree;
import org.zoodb.internal.server.index.btree.unique.UniquePagedBTree;
import org.zoodb.internal.util.Pair;

import java.util.ArrayList;
import java.util.List;

/*
 * Convenience class to build a UniqueBTree layer by layer.
 */
public class BTreeFactory {
	
	private BTree tree;
	private List<BTreeNode> prevLayer;
	private BTreeNodeFactory nodeFactory;
    private BTreeBufferManager bufferManager;
    private boolean unique = true;

    public BTreeFactory(int order, BTreeBufferManager bufferManager)  {
        this.tree = new UniquePagedBTree(order, bufferManager);
        this.nodeFactory = tree.getNodeFactory();
        this.bufferManager = bufferManager;
    }

    public BTreeFactory(int order, BTreeBufferManager bufferManager, boolean unique)  {
        createTree(order, order, bufferManager, unique);
        this.nodeFactory = tree.getNodeFactory();
        this.bufferManager = bufferManager;
    }
    
    public BTreeFactory(int innerNodeOrder, int leafNodeOrder, BTreeBufferManager bufferManager, boolean unique)  {
        createTree(innerNodeOrder, leafNodeOrder, bufferManager, unique);
        this.nodeFactory = tree.getNodeFactory();
        this.bufferManager = bufferManager;
    }

    public void addInnerLayer(List<List<Long>> nodeKeys) {
		this.addLayer(false, nodeKeys);
	}
	
	
	public void addLeafLayerDefault(List<List<Long>> nodeKeys) {
		// value is same as key
		this.addLeafLayer(zip(nodeKeys,nodeKeys));
	}
	
	public void addLeafLayer(List<List<Pair<Long,Long>>> nodeKeysValues) {
		List<List<Long>> nodeKeys = splitList(true, nodeKeysValues);
		splitList(true, nodeKeysValues);
		this.addLayer(true,nodeKeys);
		List<List<Long>> nodeValues = splitList(false, nodeKeysValues);;
		for(int i=0; i<prevLayer.size(); i++) {
			List<Long> values = nodeValues.get(i);
			prevLayer.get(i).setValues(padLongArray(toPrimitives(
									values.toArray(new Long[values.size()])), 
									this.tree.getLeafOrder()));
		}
	}
	
	public void addLayer(boolean isLeaf, List<List<Long>> nodeKeys) {
        int order = (isLeaf == true) ? tree.getLeafOrder() : tree.getInnerNodeOrder();
		if(this.tree.isEmpty()) {
			BTreeNode root = nodeFactory.newUniqueNode(this.tree.getInnerNodeOrder(), isLeaf, true);
			root.setNumKeys(nodeKeys.get(0).size());
			List<Long> keys = nodeKeys.get(0);
			root.setKeys(padLongArray(toPrimitives(
									keys.toArray(new Long[keys.size()])), 
									order));
			tree.setRoot(root);
			prevLayer = new ArrayList<BTreeNode>();
			prevLayer.add(root);
		} else {
			int indexLayer = 0;
			List<BTreeNode> newLayer = new ArrayList<BTreeNode>();
			for(BTreeNode parent : prevLayer) {
				BTreeNode[] children = new BTreeNode[order];
				for(int ik = 0; ik < parent.getNumKeys()+1; ik++) {
					BTreeNode node = nodeFactory.newUniqueNode(order, isLeaf, false);
					List<Long> keys = nodeKeys.get(indexLayer);
					node.setKeys(padLongArray(toPrimitives(
									keys.toArray(new Long[keys.size()])),
                            order));
					node.setNumKeys(keys.size());
					children[ik] = node;
					newLayer.add(node);
					indexLayer++;
				}
				parent.setChildren(padChildrenArray(children, order));
			}
			this.prevLayer = newLayer;
		}
	}

	public BTree getTree() {
		return this.tree;
	}
	
	public void clear() {
        createTree(tree.getInnerNodeOrder(), tree.getLeafOrder(), bufferManager, unique);
	}
	
	private static List<List<Pair<Long,Long>>> zip(List<List<Long>> l1, List<List<Long>> l2) {
		List<List<Pair<Long,Long>>> ret = new ArrayList<List<Pair<Long,Long>>>();
		for(int i = 0; i< Math.min(l1.size(), l2.size()); i++) {
			List<Long> l1inner = l1.get(i);
			List<Long> l2inner = l2.get(i);
			List<Pair<Long,Long>> tmp = new ArrayList<Pair<Long,Long>>();
			for(int j = 0; j< Math.min(l1inner.size(), l2inner.size()); j++) {
				tmp.add(new Pair<Long,Long>(l1inner.get(j), l2inner.get(j)));
			}
			ret.add(tmp);
		}
		return ret;
	}
		
	private static List<List<Long>> splitList(boolean first, List<List<Pair<Long,Long>>> list) {
		List<List<Long>> ret = new ArrayList<List<Long>>();
		for(List<Pair<Long,Long>> outer : list) {
			ArrayList<Long> tmp = new ArrayList<Long>();
			for(Pair<Long,Long> inner : outer) {
				tmp.add(first ? inner.getA() : inner.getB());
			}
			ret.add(tmp);
		}
		return ret;
	}
	
	private static long[] padLongArray(long[] keys, int order) {
		long[] paddedKeyArray = new long[order - 1];
		for (int i = 0; i < keys.length; i++) {
			paddedKeyArray[i] = keys[i];
		}
		for (int i = keys.length; i < order - 1; i++) {
			paddedKeyArray[i] = 0;
		}
		return paddedKeyArray;
	}

	private static BTreeNode[] padChildrenArray(BTreeNode[] children, int order) {
		BTreeNode[] paddedNodeArray = new BTreeNode[order];
		for (int i = 0; i < children.length; i++) {
			paddedNodeArray[i] = children[i];
		}
		for (int i = children.length; i < order; i++) {
			paddedNodeArray[i] = null;
		}
		return paddedNodeArray;
	}
	
	public static long[] toPrimitives(Long... objects) {
	    long[] primitives = new long[objects.length];
	    for (int i = 0; i < objects.length; i++)
	         primitives[i] = objects[i];

	    return primitives;
	}

    private void createTree(int innerNodeOrder, int leafOrder, BTreeBufferManager bufferManager, boolean unique) {
        if (unique) {
            this.tree = new UniquePagedBTree(innerNodeOrder, leafOrder, bufferManager);
        } else {
            this.tree = new NonUniquePagedBTree(innerNodeOrder, leafOrder, bufferManager);
        }
    }

}