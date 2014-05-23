package org.zoodb.internal.server.index.btree;

import org.zoodb.internal.server.index.btree.prefix.PrefixSharingHelper;

import java.util.NoSuchElementException;

/**
 * Shared behaviour of unique and non-unique B+ tree.
 */
public abstract class BTree<T extends BTreeNode> {

    protected T root;
    protected BTreeNodeFactory nodeFactory;
    protected int pageSize;
    private long maxKey = Long.MIN_VALUE;
    private long minKey = Long.MIN_VALUE;
    
    private int modcount = 0; // number of modifications of the tree
    
    public BTree(int pageSize, BTreeNodeFactory nodeFactory) {
    	this(null, pageSize, nodeFactory);
        this.root = (T) nodeFactory.newNode(isUnique(), getPageSize(), true, true);
        this.root.recomputeSize();
    }
    
    public BTree(T root, int pageSize, BTreeNodeFactory nodeFactory) {
	    this.root = root;
        if (root != null) {
            this.root.recomputeSize();
        }

        this.pageSize = pageSize;
        this.nodeFactory = nodeFactory;
	}
    
    public abstract boolean isUnique();

    /**
     * Insert a new key value pair to the B+ tree.
     *
     * Algorithm performs as follows:
     *  - a reference to the leaf node on which the value for the key received as argument is first retrieved
     *  - if the leaf will not overflow after adding the new (key, value) pair, the pair is inserted in the leaf.
     *  - if the leaf will overflow after the addition of the new (key, value) pair, the leaf is split into 2 leaves.
     *    The keys/values in the original leaf are split as evenly as possible between the 2 leaves.
     *    The references to the parent node are then fixed.
     * @param key               The new key to be inserted
     * @param value         The new value to be inserted
     */
    public void insert(long key, long value) {
        insert(root, key, value);
        increaseModcount();
        if (root.overflows()) {
            handleRootOverflow();
        }
        recomputeMinAndMaxAfterInsert(key);
    }


    public void insert(T node, long key, long value) {
        node.markChanged();
        if (node.isLeaf()) {
            node.put(key, value);
        } else {
            int childIndex = node.findKeyValuePos(key, value);
            T child = node.getChild(childIndex);
            insert(child, key, value);

            if (child.overflows()) {
//                T leftSibling = node.leftSibling(childIndex);
//                if (leftSibling != null && !leftSibling.willOverflowAfterInsert(child.getSmallestKey(), child.getSmallestValue())) {
//                    redistributeKeysFromRight(leftSibling, child, node, childIndex);
//                } else if (child.overflows()) {
                    handleInsertOverflow(child, node, childIndex);
                //}
            }
            node.setChildSize(child.getCurrentSize(), childIndex);
        }
    }

    private void handleRootOverflow() {
        T newRoot = (T) nodeFactory.newNode(isUnique(), getPageSize(), false, true);

        T right;
        T left = root;
        swapRoot(newRoot);
        if (left.isLeaf()) {
            right = split(left);
            root.put(right.getSmallestKey(), right.getSmallestValue(), left, right);
        } else {
            putInnerNodeInRoot(left);
        }
    }

    private void handleInsertOverflow(T child, T parent, int childIndex) {
        if (child.isLeaf()) {
            putLeafInParent(child, parent, childIndex);
        } else {
            putInnerNodeInParent(child, parent, childIndex);
        }
    }

    private void putLeafInParent(T child, T parent, int childIndex) {
        T right = split(child);
        parent.put(right.getSmallestKey(), right.getSmallestValue(), childIndex, right);
    }

    private void putInnerNodeInParent(T child, T parent, int childIndex) {
        int numKeys = child.getNumKeys();

        int weightKey = (child.isLeaf() || (!isUnique())) ? child.getValueElementSize() : 0;
        int weightChild = (child.isLeaf() ? 0 : 4);
        int header = child.storageHeaderSize();
        int keysInLeftNode = PrefixSharingHelper.computeIndexForSplitAfterInsert(
                child.getKeys(), child.getNumKeys(),
                header, weightKey, weightChild, getPageSize());

        long newKey = child.getKey(keysInLeftNode);
        long newValue = child.getValue(keysInLeftNode);

        int keysInRightNode = numKeys - keysInLeftNode - 1;

        // populate right node
        T right = (T) nodeFactory.newNode(isUnique(), pageSize, child.isLeaf(), false);
        child.copyFromNodeToNode(keysInLeftNode + 1, keysInLeftNode + 1, right,
                0, 0, keysInRightNode, keysInRightNode + 1);
        right.setNumKeys(keysInRightNode);
        child.setNumKeys(keysInLeftNode);

        child.recomputeSize();
        right.recomputeSize();

        parent.put(newKey, newValue, childIndex, right);
    }

    private void putInnerNodeInRoot(T child) {
        int numKeys = child.getNumKeys();

        int weightKey = (child.isLeaf() || (!isUnique())) ? child.getValueElementSize() : 0;
        int weightChild = (child.isLeaf() ? 0 : 4);
        int header = child.storageHeaderSize();
        int keysInLeftNode = PrefixSharingHelper.computeIndexForSplitAfterInsert(
                child.getKeys(), child.getNumKeys(),
                header, weightKey, weightChild, getPageSize());

        long newKey = child.getKey(keysInLeftNode);
        long newValue = child.getValue(keysInLeftNode);

        int keysInRightNode = numKeys - keysInLeftNode - 1;

        // populate right node
        T right = (T) nodeFactory.newNode(isUnique(), pageSize, child.isLeaf(), false);
        child.copyFromNodeToNode(keysInLeftNode + 1, keysInLeftNode + 1, right,
                0, 0, keysInRightNode, keysInRightNode + 1);
        right.setNumKeys(keysInRightNode);
        child.setNumKeys(keysInLeftNode);

        child.recomputeSize();
        right.recomputeSize();

        root.put(newKey, newValue, child, right);
    }

    /**
     * Split the current leaf node into two nodes. The first half of the keys remain on
     * the current node, the rest are moved to a new node.
     *
     * @param current               A node that overflows and needs to be split.
     * @return                      The new node that contains the right half
     *                              of the keys of the current node.
     */
    private T split(T current) {
        int numKeys = current.getNumKeys();

        int weightKey = (current.isLeaf() || (!isUnique())) ? current.getValueElementSize() : 0;
        int weightChild = (current.isLeaf() ? 0 : 4);
        int header = current.storageHeaderSize();
        int keysInLeftNode = PrefixSharingHelper.computeIndexForSplitAfterInsert(
                current.getKeys(), current.getNumKeys(),
                header, weightKey, weightChild, getPageSize());

        int keysInRightNode = numKeys - keysInLeftNode;

        // populate right node
        T right = (T) nodeFactory.newNode(isUnique(), pageSize, current.isLeaf(), false);
        current.copyFromNodeToNode(keysInLeftNode, keysInLeftNode, right,
                0, 0, keysInRightNode, keysInRightNode + 1);
        right.setNumKeys(keysInRightNode);
        current.setNumKeys(keysInLeftNode);

        current.recomputeSize();
        right.recomputeSize();

        return right;
    }

    /**
     * Deletes a key/value pair from a tree and then
     * performs a re-balance operation.
     * @param key
     * @param value
     * @return
     */
	protected long deleteEntry(long key, long value) {
		if(root.getNumKeys() == 0) {
			throw new NoSuchElementException();
		}

        increaseModcount();
        long oldValue = delete(root, key, value);

        recomputeMinAndMax(key);
        return oldValue;
    }

    /**
     * Delete a key/value pair the sub-tree rooted at node.
     *
     *
     * @param node
     * @param key
     * @param value
     * @return                  The value associated with the key.
     */
    private long delete(T node, long key, long value) {
        node.markChanged();
        long oldValue;
        if (node.isLeaf()) {
            oldValue = deleteFromLeaf(node, key, value);
        } else {
            int childIndex = node.findKeyValuePos(key, value);
            T child = node.getChild(childIndex);
            oldValue = delete(child, key, value);
            node.setChildSize(child.getCurrentSize(), childIndex);

            if (child.isUnderfull()) {
                rebalance(node, child, childIndex);
            } else if (child.overflows()) {
                handleInsertOverflow(child, node, childIndex);
            }
        }
        return oldValue;
    }

    /**
     * Re-balance the key/value pairs from the tree after a deletion.
     *
     * If the node from which the deletion was done
     * is under-full, check if it can be merged with the left
     * or the right sibling. If that is not possible, attempt to redistribute some keys
     * from the left or right neighbour to avoid having nodes which are underfull
     *
     *
     * @param child                 The node from which the deletion has been made
     * @param node                  The parent of the child node
     * @param childIndex                   The index of the child node in the parent node.
     */
     private void rebalance(T node, T child, int childIndex) {
         //check if can borrow 1 value from the left or right siblings
         T rightSibling = node.rightSibling(childIndex);
         T leftSibling = node.leftSibling(childIndex);
         if (child.fitsIntoOneNodeWith(leftSibling)) {
             mergeWithLeft(this, child, leftSibling, node, childIndex - 1);
         } else if (child.fitsIntoOneNodeWith(rightSibling)) {
             mergeWithRight(this, child, rightSibling, node, childIndex);
         } else if (leftSibling != null && leftSibling.hasExtraKeys()) {
             redistributeKeysFromLeft(child, leftSibling, node, childIndex - 1);
         } else if (rightSibling != null && rightSibling.hasExtraKeys()) {
             redistributeKeysFromRight(child, rightSibling, node, childIndex);
         }
     }

    /**
     * Removed a key/value pair from a leaf node and return the previous value.
     *
     * The oldValue is useful for unique trees. In such a case, the
     * value would be ignored and the removal would be done based on
     * the key.
     *
     * @param leaf
     * @param key
     * @param value
     * @return
     */
    protected long deleteFromLeaf(T leaf, long key, long value) {
        long oldValue = leaf.delete(key, value);
        leaf.recomputeSize();
        return oldValue;
    }

    public void setRoot(BTreeNode root) {
        this.root = (T) root;
    }

    public boolean isEmpty() {
        return root.getNumKeys() == 0;
    }

    public T getRoot() {
        return this.root;
    }

    public String toString() {
        if(this.root != null) {
            return this.root.toString();
        } else {
            return "Empty tree";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BTree)) return false;

        BTree tree = (BTree) o;

        if (pageSize != tree.getPageSize()) return false;
        if (root != null ? !root.equals(tree.getRoot()) : tree.getRoot() != null) return false;

        return true;
    }

    public void swapRoot(T newRoot) {
        if (root != null) {
            root.setIsRoot(false);
        }
        setRoot(newRoot);
        if (newRoot != null) {
            newRoot.setIsRoot(true);
        }
    }

    public BTreeNodeFactory getNodeFactory() {
        return nodeFactory;
    }

    /*
     * Counts number of nodes in the tree.
     * WARNING: SLOW! has to iterate over whole tree
     */
    public int size() {
        BTreeIterator it = new BTreeIterator(this);
        int counter = 0;
        while(it.hasNext()) {
            it.next();
            counter++;
        }

        return counter;
    }

    /**
     * Merge the current node into the right node. After this operations is completed,
     * the right node will contain the key/value entries corresponding to the current node
     * together with its previous entries.
     *
     * The current node reference is also removed from the parent node.
     *
     * @param tree                  The tree on which to perform the operation
     * @param current               The current node
     * @param right                 The right sibling of the current node
     * @param parent                The parent of the current node
     * @return                      A reference to the parent node
     */
    public T mergeWithRight(BTree<T> tree, T current, T right, T parent, int keyIndex) {

        assert keyIndex == parent.keyIndexOf(current, right);

        //check if parent needs merging -> tree gets smaller
        if (parent.isRoot() && parent.getNumKeys() == 1) {
            parent = rootMergeWithRight(tree, current, right, parent);
        } else {
            if (right.isLeaf()) {
                leafMergeWithRight(current, right, parent, keyIndex);
            } else {
                innerMergeWithRight(current, right, parent, keyIndex);
            }
        }
        //this node will not be used anymore
        current.close();
        parent.setChildSize(right.getCurrentSize(), keyIndex);

        return parent;
    }

    /**
     * Merge the current node into the left node. After this operations is completed,
     * the current node will contain the key/value entries corresponding to the left node
     * together with its previous entries.
     *
     * The left node reference is also removed from the parent node.
     * @param tree                      The tree on which to perform the operation
     * @param current                   The current leaf node
     * @param left                      The sibling of the current node
     * @param parent                    The parent of the current node
     * @return                          A new reference to the current node
     */
    public T  mergeWithLeft(BTree<T> tree, T current, T left, T parent, int keyIndex) {

        //assert keyIndex == parent.keyIndexOf(left, current);

        //check if we need to merge with parent
        if (parent.getNumKeys() == 1 && parent.isRoot()) {
            parent = rootMergeWithLeft(tree, current, left, parent);
        } else {
            if (current.isLeaf()) {
                leafMergeWithLeft(current, left, parent, keyIndex);
            } else {
                innerMergeWithLeft(current, left, parent, keyIndex);
            }
        }
        //left wont be used anymore
        left.close();

        parent.setChildSize(current.getCurrentSize(), keyIndex + 1);
        return parent;
    }

    /**
     * Move some of the keys from the right sibling of the current node
     * to the current node. Should be called when the node from which a
     * deletion has been made is underfull.
     *
     * @param current           The current node
     * @param right             The right sibling of the current node
     * @param parent            The parent of the current node
     */
    public void redistributeKeysFromRight(T current, T right, T parent, int parentKeyIndex) {
        int keysToMove = computeKeysToMoveFromRight(current, right);
        if (keysToMove == 0) {
            return;
        }
        //move key from parent to current node
        //assert parentKeyIndex == parent.keyIndexOf(current, right);

        if (current.isLeaf()) {
            leafRedistributeFromRight(current, right, parent, parentKeyIndex, keysToMove);
        } else {
            keysToMove--;
            innerRedistributeFromRight(current, right, parent, parentKeyIndex, keysToMove);
        }
        parent.setChildSize(current.getCurrentSize(), parentKeyIndex);
        parent.setChildSize(right.getCurrentSize(), parentKeyIndex + 1);
    }

    /**
     * Move some of the keys from the left sibling of the current node
     * to the current node. Should be called when the node from which a
     * deletion has been made is underfull.
     *
     * @param current           The current node
     * @param left              The left sibling of the current node
     * @param parent            The parent of the current node
     */
    public void redistributeKeysFromLeft(T current, T left, T parent, int parentKeyIndex) {
        int keysToMove = computeKeysToMoveFromLeft(current, left);
        //assert parentKeyIndex == parent.keyIndexOf(left, current);
        if (current.isLeaf()) {
            if (keysToMove <= 0) {
                return;
            }
            leafRedistributeFromLeft(current, left, parent, parentKeyIndex, keysToMove);
        } else {
            keysToMove = (current.getNumKeys() == 0) ? keysToMove - 3 : keysToMove - 2;
            if (keysToMove <= 0) {
                return;
            }
            innerRedistributeFromLeft(current, left, parent, parentKeyIndex, keysToMove);
        }
        parent.setChildSize(left.getCurrentSize(), parentKeyIndex);
        parent.setChildSize(current.getCurrentSize(), parentKeyIndex + 1);

    }

    private int computeKeysToMoveFromLeft(T current, T left) {
        int weightKey = (current.isLeaf() || (!isUnique())) ? current.getValueElementSize() : 0;
        int weightChild = (current.isLeaf() ? 0 : 4);
        int header = current.storageHeaderSize();

        int splitIndexInLeft = PrefixSharingHelper.computeIndexForRedistributeLeftToRight(
                left.getKeys(), left.getNumKeys(), current.getKeys(), current.getNumKeys(),
                header, weightKey, weightChild, current.getPageSize());

        int keysToMove = left.getNumKeys() - splitIndexInLeft;
        return keysToMove;
    }

    private int computeKeysToMoveFromRight(T current, T right) {
        int weightKey = (current.isLeaf() || (!isUnique())) ? current.getValueElementSize() : 0;
        int weightChild = (current.isLeaf() ? 0 : 4);
        int header = current.storageHeaderSize();

        int splitIndexInRight = PrefixSharingHelper.computeIndexForRedistributeRightToLeft(
                current.getKeys(), current.getNumKeys(), right.getKeys(), right.getNumKeys(),
                header, weightKey, weightChild, current.getPageSize());

        int keysToMove = splitIndexInRight + 1;
        return keysToMove;
    }

    private T leafRedistributeFromLeft(T current, T left, T parent, int parentKeyIndex, int keysToMove) {
        //shift nodes in current node right
        current.shiftRecordsRight(keysToMove);

        int startIndexLeft = left.getNumKeys() - keysToMove;
        int startIndexRight = 0;
        //copy from left to current
        copyRedistributeFromLeftNodeToRightNode(left, startIndexLeft, current, startIndexRight, keysToMove, keysToMove);

        //fix number of keys
        left.decreaseNumKeys(keysToMove);
        left.recomputeSize();
        current.increaseNumKeys(keysToMove);
        current.recomputeSize();

        //move key from parent to current node
        parent.migrateEntry(parentKeyIndex, current, 0);
        parent.recomputeSize();
        return parent;
    }

    private T innerRedistributeFromLeft(T current, T left, T parent, int parentKeyIndex, int keysToMove) {
        int startIndexLeft = left.getNumKeys() - keysToMove;
        int startIndexRight = 0;

        current.shiftRecordsRight(1);
        current.increaseNumKeys(1);
        current.migrateEntry(0, parent, parentKeyIndex);
        //shift nodes in current node right
        current.shiftRecordsRight(keysToMove);

        //copy k keys and k+1 children from left
        left.copyFromNodeToNode(startIndexLeft, startIndexLeft, current,
                startIndexRight, startIndexRight, keysToMove, keysToMove + 1);
        current.increaseNumKeys(keysToMove);
        current.recomputeSize();
        left.decreaseNumKeys(keysToMove);

        //move the biggest key to parent
        parent.migrateEntry(parentKeyIndex, left, left.getNumKeys() - 1);
        parent.recomputeSize();
        left.decreaseNumKeys(1);
        left.recomputeSize();
        return parent;
    }

    private T leafRedistributeFromRight(T current, T right, T parent, int parentKeyIndex, int keysToMove) {
        int startIndexRight = 0;
        int startIndexLeft = current.getNumKeys();
        //copy from left to current
        copyFromRightNodeToLeftNode(right, startIndexRight, current, startIndexLeft, keysToMove, keysToMove);

        //shift nodes in current node right
        right.shiftRecordsLeft(keysToMove);
        //fix number of keys
        right.decreaseNumKeys(keysToMove);
        current.increaseNumKeys(keysToMove);

        parent.migrateEntry(parentKeyIndex, right, 0);
        parent.recomputeSize();
        current.recomputeSize();
        right.recomputeSize();

        return parent;
    }

    private T innerRedistributeFromRight(T current, T right, T parent, int parentKeyIndex, int keysToMove) {
        if (keysToMove != 0) {
            //add key from parent
            current.migrateEntry(current.getNumKeys(), parent, parentKeyIndex);
            current.increaseNumKeys(1);

            int startIndexRight = 0;
            int startIndexLeft = current.getNumKeys();

            //copy from left to current
            copyFromRightNodeToLeftNode(right, startIndexRight, current, startIndexLeft, keysToMove, keysToMove + 1);
            current.increaseNumKeys(keysToMove);
            current.recomputeSize();

            //shift nodes in current node right
            right.shiftRecordsLeft(keysToMove);
            right.decreaseNumKeys(keysToMove);

            parent.migrateEntry(parentKeyIndex, right, 0);
            parent.recomputeSize();

            right.shiftRecordsLeft(1);
            right.decreaseNumKeys(1);
            right.recomputeSize();
        }
        return parent;
    }

    private T leafMergeWithLeft(T current, T left, T parent, int keyIndex) {
        //leaf node merge
        parent.shiftRecordsLeftWithIndex(keyIndex, 1);
        parent.decreaseNumKeys(1);
        parent.recomputeSize();

        current.shiftRecordsRight(left.getNumKeys());
        copyNodeToAnother(left, current, 0);
        current.increaseNumKeys(left.getNumKeys());

        current.recomputeSize();
        return parent;
    }

    private T innerMergeWithLeft(T current, T left, T parent, int keyIndex) {
        //inner node merge
        //move key from parent
        current.shiftRecordsRight(left.getNumKeys() + 1);
        current.migrateEntry(left.getNumKeys(), parent, keyIndex);
        parent.shiftRecordsLeftWithIndex(keyIndex, 1);
        parent.decreaseNumKeys(1);
        parent.recomputeSize();

        //copy from left node
        copyNodeToAnother(left, current, 0);
        current.increaseNumKeys(left.getNumKeys() + 1);

        current.recomputeSize();
        return parent;
    }

    private T rootMergeWithRight(BTree<T> tree, T current, T right, T parent) {
        if (!current.isLeaf()) {
            right.shiftRecordsRight(parent.getNumKeys());
            right.migrateEntry(0, parent, 0);
            right.increaseNumKeys(1);
        }

        right.shiftRecordsRight(current.getNumKeys());
        copyMergeFromLeftNodeToRightNode(current, 0, right, 0, current.getNumKeys(), current.getNumKeys());
        right.increaseNumKeys(current.getNumKeys());
        tree.swapRoot(right);
        parent.close();
        parent = right;

        parent.recomputeSize();
        return parent;
    }

    private T rootMergeWithLeft(BTree<T> tree, T current, T left, T parent) {
        if (!current.isLeaf()) {
            current.shiftRecordsRight(parent.getNumKeys());
            current.migrateEntry(0, parent, 0);
            current.increaseNumKeys(parent.getNumKeys());
        }

        current.shiftRecordsRight(left.getNumKeys());
        copyNodeToAnother(left, current, 0);
        current.increaseNumKeys(left.getNumKeys());
        tree.swapRoot(current);
        parent.close();
        parent = current;

        parent.recomputeSize();
        return parent;
    }

    private T leafMergeWithRight(T current, T right, T parent, int keyIndex) {
        //merge leaves
        parent.shiftRecordsLeftWithIndex(keyIndex, 1);
        parent.decreaseNumKeys(1);
        parent.recomputeSize();

        right.shiftRecordsRight(current.getNumKeys());
        copyMergeFromLeftNodeToRightNode(current, 0, right, 0, current.getNumKeys(), current.getNumKeys());
        right.increaseNumKeys(current.getNumKeys());
        right.recomputeSize();

        return parent;
    }

    private T innerMergeWithRight(T current, T right, T parent, int keyIndex) {
        //merge inner nodes
        right.shiftRecordsRight(1);
        right.migrateEntry(0, parent, keyIndex);
        right.increaseNumKeys(1);
        parent.shiftRecordsLeftWithIndex(keyIndex, 1);
        parent.decreaseNumKeys(1);
        parent.recomputeSize();

        right.shiftRecordsRight(current.getNumKeys());
        copyMergeFromLeftNodeToRightNode(current, 0, right, 0, current.getNumKeys(), current.getNumKeys());
        right.increaseNumKeys(current.getNumKeys());
        right.recomputeSize();

        return parent;
    }

    public void copyMergeFromLeftNodeToRightNode(T src, int srcStart, T dest, int destStart, int keys, int children) {
        src.copyFromNodeToNode(srcStart, srcStart, dest, destStart, destStart, keys, children + 1);
    }

    public void copyRedistributeFromLeftNodeToRightNode(T src, int srcStart, T dest, int destStart,
                                                        int keys, int children) {
        src.copyFromNodeToNode( srcStart, srcStart + 1, dest, destStart, destStart, keys, children);
    }
    
    public long getMaxKey() {
		return maxKey;
	}

	public long getMinKey() {
		return minKey;
	}

	public long computeMinKey() {
        //Todo make this faster by executing this code right when the deletion is done
        if (getRoot().getNumKeys() == 0) {
            return -1;
        }
        BTreeLeafEntryIterator<T> it = new AscendingBTreeLeafEntryIterator<T>(this);
        long minKey = Long.MIN_VALUE;
        if(it.hasNext()) {
                minKey = it.next().getKey();
        }
        return minKey;
    }

    public long computeMaxKey() {
        //ToDo cleanup code
        if (getRoot().getNumKeys() == 0) {
            return Long.MIN_VALUE;
        }
        BTreeLeafEntryIterator<T> it = new DescendingBTreeLeafEntryIterator<T>(this);
        long maxKey = Long.MIN_VALUE;
        if(it.hasNext()) {
                maxKey = it.next().getKey();
        }
        return maxKey;
    }
    
    public int statsGetInnerN() {
    	BTreeIterator it = new BTreeIterator(this);
		int innerN = 0;
		while(it.hasNext()) {
			if(!it.next().isLeaf()) innerN++;
		}
		return innerN;
    }
    
    public int statsGetLeavesN() {
    	BTreeIterator it = new BTreeIterator(this);
		int leafN = 0;
		while(it.hasNext()) {
			if(it.next().isLeaf()) leafN++;
		}
		return leafN;
    }
    
    private void copyFromRightNodeToLeftNode(T src,  int srcStart, T dest, int destStart,
                                             int keys, int children) {
        src.copyFromNodeToNode(srcStart, srcStart, dest, destStart, destStart, keys, children);
    }

    private void copyNodeToAnother(T source, T destination, int destinationIndex) {
        source.copyFromNodeToNode(0, 0, destination, destinationIndex, destinationIndex, source.getNumKeys(), source.getNumKeys() + 1);
    }

    private void increaseModcount() {
    	modcount++;
	}
    
    public int getModcount() {
    	return this.modcount;
    }

    public int getPageSize() {
        return pageSize;
    }

    private void recomputeMinAndMaxAfterInsert(long newKey) {
        minKey = Math.min(newKey, minKey);
        maxKey = Math.max(newKey, maxKey);
    }

    private void recomputeMinAndMax(long deletedKey) {
        if(deletedKey == minKey) {
            minKey = computeMinKey();
        }
        if(deletedKey == maxKey) {
            maxKey = computeMaxKey();
        }
    }
}
