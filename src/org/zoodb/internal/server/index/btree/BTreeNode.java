package org.zoodb.internal.server.index.btree;

import org.zoodb.internal.util.Pair;

import java.util.Observable;

/**
 * Represents the node of a B+ tree.
 * 
 * Support for linked-lists of nodes on the leaf level is yet to be added.
 */
public abstract class BTreeNode extends Observable {

	private boolean isLeaf;
	private boolean isRoot;
	protected int order;

	// ToDo maybe we want to have the keys set dynamically sized somehow
	protected int numKeys;
	private long[] keys;

	private long[] values;

	public BTreeNode(int order, boolean isLeaf, boolean isRoot) {
		this.order = order;
		this.isLeaf = isLeaf;
		this.isRoot = isRoot;

		initializeEntries(order);
	}

    public abstract void initializeEntries(int order);

    public abstract BTreeNode newNode(int order, boolean isLeaf, boolean isRoot);
    public abstract boolean equalChildren(BTreeNode other);
    public abstract void copyChildren(BTreeNode source, int sourceIndex,
                                      BTreeNode dest, int destIndex, int size);
    protected abstract <T extends BTreeNode> T  leftSiblingOf(BTreeNode node);
    protected abstract <T extends BTreeNode> T  rightSiblingOf(BTreeNode node);
    public abstract <T extends BTreeNode> T getChild(int index);
    public abstract void setChild(int index, BTreeNode child);
    public abstract BTreeNode[] getChildren();
    public abstract void setChildren(BTreeNode[] children);
    public abstract void markChanged();

    // closes (destroys) node
    public abstract void close();
    /*
        Node modification operations
     */
    public abstract void migrateEntry(int destinationPos, BTreeNode source, int sourcePos);
    public abstract void setEntry(int pos, long key, long value);

    public abstract void copyFromNodeToNode(int srcStartK, int srcStartC, BTreeNode destination, int destStartK, int destStartC, int keys, int children);
    public abstract void shiftRecords(int startIndex, int endIndex, int amount);
    public abstract void shiftRecordsRight(int amount);
    public abstract void shiftRecordsLeftWithIndex(int startIndex, int amount);
    protected abstract boolean containsAtPosition(int position, long key, long value);
    protected abstract boolean smallerThanKeyValue(int position, long key, long value);
    protected abstract boolean allowNonUniqueKeys();
    protected abstract void resizeEntries(int order);
    protected abstract void resizeChildren(int order);

    public void put(long key, long value) {
        if (!isLeaf()) {
            throw new IllegalStateException(
                    "Should only be called on leaf nodes.");
        }

        int pos = this.findKeyValuePos(key, value);
        if (checkIllegalInsert(pos, key, value)) {
            throw new IllegalStateException(
                    "Tree is not allowed to have non-unique key-value pairs.");
        }
        if(!allowNonUniqueKeys() && pos > 0 && getKey(pos-1) == key) {
        	pos -=1;
        } else {
            shiftRecords(pos, pos + 1, getNumKeys() - pos);
            incrementNumKeys();
        }
        setKey(pos, key);
        setValue(pos, value);

        //signal change
        markChanged();
    }
    
    private boolean checkIllegalInsert(int position, long key, long value) {
        return position > 0 && (getKey(position - 1) == key && getValue(position - 1) == value);
    }

    public <T extends BTreeNode> T findChild(long key, long value) {
        return getChild(findKeyValuePos(key, value));
    }

    public boolean containsKeyValue(long key, long value) {
        Pair<Boolean, Integer> result = binarySearch(key, value);
        return result.getA();
    }

    public int findKeyValuePos(long key, long value) {
        if (getNumKeys() == 0) {
            return 0;
        }
        Pair<Boolean, Integer> result = binarySearch(key, value);
        int closest = result.getB();
        boolean found = result.getA();

        // if the key is not here, find the child subtree that has it
        if (!found) {
            //TODO need to change for key and value for non-unique
            if (closest == 0 && smallerThanKeyValue(0, key, value)) {
            //if (closest == 0 && key < getKey(0)) {
                return 0;
            } else if (smallerThanKeyValue(closest, key, value)) {
                //if (key < getKey(closest)) {
                return closest;
            }
        }
        return closest + 1;
    }

    /**
     * Inner-node put. Places key to the left of the next bigger key k'.
     *
     * Requires that key <= keys(newUniqueNode) all elements of the left child of k'
     * are smaller than key node is not full. Assumes that leftOf(key') <=
     * keys(newUniqueNode)
     *
     * @param key
     * @param newNode
     */
    public void put(long key, long value, BTreeNode newNode) {
        if (isLeaf()) {
            throw new IllegalStateException(
                    "Should only be called on inner nodes.");
        } else if (getNumKeys() == 0) {
            throw new IllegalStateException(
                    "Should only be called when node is non-empty.");
        }
        int pos = findKeyValuePos(key, value);
        if (pos > 0 && (getKey(pos - 1) == key && getValue(pos - 1) == value)) {
            throw new IllegalStateException(
                    "Tree is not allowed to have non-unique values.");
        }
        int recordsToMove = getNumKeys() - pos;
        shiftChildren(pos + 1, pos + 2, recordsToMove);
        setChild(pos + 1, newNode);

        shiftKeys(pos, pos + 1, recordsToMove);
        if (values != null) {
            shiftValues(pos, pos + 1, recordsToMove);
        }
        setEntry(pos, key, value);
        incrementNumKeys();
    }

    /**
     * Root-node put.
     *
     * Used when a non-leaf root is empty and will be populated by a single key
     * and two nodes.
     *
     * @param key
     *            The new key on the root.
     * @param left
     *            The left node.
     * @param right
     *            The right node.
     */
    public <T extends BTreeNode> void put(long key, long value,  T left, T right) {
        if (!isRoot()) {
            throw new IllegalStateException(
                    "Should only be called on the root node.");
        }
        setEntry(0, key, value);
        setNumKeys(1);

        setChild(0, left);
        setChild(1, right);
    }



    /**
     * Delete the key from the node.
     *
     * @param key
     */
    public long delete(long key, long value) {
        if (!isLeaf()) {
            throw new IllegalStateException("Should be a leaf node");
        }
        final int keyPos = findKeyValuePos(key, value);
        int recordsToMove = getNumKeys() - keyPos;
        long oldValue = getValue(keyPos - 1);
        shiftRecords(keyPos, keyPos - 1, recordsToMove);
        decrementNumKeys();
        return oldValue;
    }

    public void replaceEntry(long key, long value, long replacementKey, long replacementValue) {
        if (replacementKey < key) {
            throw new RuntimeException("Replacing " + key + " with "
                    + replacementKey + " might be illegal.");
        }
        int pos = findKeyValuePos(key, value);
        if (pos > -1) {
            setEntry(pos - 1, replacementKey, replacementValue);
        }
    }

    public BTreeNode leftSibling(BTreeNode parent) {
        return (parent == null) ? null : parent.leftSiblingOf(this);
    }

    public BTreeNode rightSibling(BTreeNode parent) {
        return (parent == null) ? null : parent.rightSiblingOf(this);
    }

	public void setKey(int index, long key) {
		getKeys()[index] = key;

        //signal change
        markChanged();
	}

	public void setValue(int index, long value) {
		getValues()[index] = value;

        //signal change
        markChanged();
	}

	public abstract int keyIndexOf(BTreeNode left, BTreeNode right);

	public boolean isUnderfull() {
		if (isRoot()) {
			return getNumKeys() == 0;
		}
		if (isLeaf()) {
			return getNumKeys() < minKeysAmount();
		}
		return getNumKeys() + 1 < order / 2.0;
	}

	public boolean hasExtraKeys() {
		if (isRoot()) {
			return true;
		}
		return getNumKeys() - 1 >= minKeysAmount();
	}

	public boolean isOverflowing() {
		return getNumKeys() >= order;
	}

    public boolean isFull() {
        return getNumKeys() >= order - 1;
    }

	public boolean incrementNumKeys() {
        markChanged();
		return increaseNumKeys(1);
	}

	public boolean decrementNumKeys() {
        markChanged();
		return decreaseNumKeys(1);
	}

	public boolean increaseNumKeys(int amount) {
        markChanged();
		int newNumKeys = getNumKeys() + amount;
		if (newNumKeys > getKeys().length) {
			return false;
		}
		setNumKeys(newNumKeys);
		return true;
	}

	public boolean decreaseNumKeys(int amount) {
        markChanged();
		int newNumKeys = getNumKeys() - amount;
		if (newNumKeys < 0) {
			return false;
		}
		setNumKeys(newNumKeys);
		return true;
	}

	protected void initKeys(int order) {
		setKeys(new long[order - 1]);
		setNumKeys(0);
	}

    protected void initValues(int order) {
		setValues(new long[order - 1]);
	}

    protected void initChildren(int order) {
		setChildren(new BTreeNode[order]);
	}

	public long getValue(int index) {
		return getValues()[index];
	}

	public long getKey(int index) {
		return getKeys()[index];
	}

	public double minKeysAmount() {
		return (order - 1) / 2.0D;
	}

	public boolean isLeaf() {
		return isLeaf;
	}

	public boolean isRoot() {
		return isRoot;
	}

	public long getSmallestKey() {
		return keys[0];
	}

	public long getLargestKey() {
		return keys[numKeys - 1];
	}

    public long getSmallestValue() {
        return (values != null) ? values[0] : -1;
    }

    public long getLargestValue() {
        return (values != null) ? values[numKeys -1] : -1;
    }

	public int getNumKeys() {
		return numKeys;
	}

	public long[] getKeys() {
		return keys;
	}

	public long[] getValues() {
		return values;
	}

	public void setNumKeys(int numKeys) {
        markChanged();
		this.numKeys = numKeys;
	}

	public void setKeys(long[] keys) {
        markChanged();
		this.keys = keys;
	}

	public void setValues(long[] values) {
        markChanged();
		this.values = values;
	}

	public int getOrder() {
		return order;
	}

	public void setIsRoot(boolean isRoot) {
        markChanged();
		this.isRoot = isRoot;
	}

    public Pair<Long, Long> getKeyValue(int position) {
        Long key = getKey(position);
        Long value = (getValues() != null) ? getValue(position) : -1;
        return new Pair<>(key, value);
    }

    public BTreeNode leftMostLeafOf() {
		if(isLeaf()) {
			return this;
		}
		BTreeNode node = this;
		while(!node.isLeaf()) {
			node = node.getChild(0);
		}
		return node;
	}

    public BTreeNode rightMostLeafOf() {
        if(isLeaf()) {
            return this;
        }
        BTreeNode node = this;
        while(!node.isLeaf()) {
            node = node.getChild(0);
        }
        return node;
    }

    /**
     * Perform binary search on the key array for a certain key/value pair.
     *
     *
     * @param key   The key received as an argument.
     * @param value The value received as argument. Not used for decisions for unique trees.

     */
    public Pair<Boolean, Integer> binarySearch(long key, long value) {
        int low = 0;
        int high = this.getNumKeys() - 1;
        int mid = 0;
        boolean found = false;
        while (!found && low <= high) {
            mid = low + (high - low) / 2;
            if (containsAtPosition(mid, key, value)) {
                found = true;
            } else {
                if (smallerThanKeyValue(mid, key, value)) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }
        return new Pair<>(found, mid);
    }

    protected void shiftRecordsLeft(int amount) {
        markChanged();
        shiftRecordsLeftWithIndex(0, amount);
    }

    public void shiftKeys(int startIndex, int endIndex, int amount) {
        markChanged();
        System.arraycopy(getKeys(), startIndex, getKeys(), endIndex, amount);
    }

    protected void shiftValues(int startIndex, int endIndex, int amount) {
        markChanged();
        System.arraycopy(getValues(), startIndex, getValues(), endIndex, amount);
    }

    public void shiftChildren(int startIndex, int endIndex, int amount) {
        markChanged();
        copyChildren(this, startIndex, this, endIndex, amount);
    }

    public String toString() {
        String ret = (isLeaf() ? "leaf" : "inner") + "-node: k:";
        ret += "[";
        for (int i = 0; i < this.getNumKeys(); i++) {
            ret += Long.toString(keys[i]);
            if (i != this.getNumKeys() - 1)
                ret += " ";
        }
        ret += "]";
        ret += ",   \tv:";
        ret += "[";
        for (int i = 0; i < this.getNumKeys(); i++) {
            ret += Long.toString(values[i]);
            if (i != this.getNumKeys() - 1)
                ret += " ";
        }
        ret += "]";

        if (!isLeaf()) {
            ret += "\n\tc:";
            if (this.getNumKeys() != 0) {
                for (int i = 0; i < this.getNumKeys() + 1; i++) {
                    String[] lines = this.getChild(i).toString()
                            .split("\r\n|\r|\n");
                    for (String l : lines) {
                        ret += "\n\t" + l;
                    }
                }
            }
        }
        return ret;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BTreeNode))
            return false;

        BTreeNode bTreeNode = (BTreeNode) o;

        if (isLeaf() != bTreeNode.isLeaf())
            return false;
        if (getNumKeys() != bTreeNode.getNumKeys())
            return false;
        if (order != bTreeNode.order)
            return false;
        if (!isLeaf() && !equalChildren(bTreeNode))
            return false;
        if (!arrayEquals(getKeys(), bTreeNode.getKeys(), getNumKeys()))
            return false;
        // checking for parent equality would result in infinite loop
        if (!arrayEquals(getValues(), bTreeNode.getValues(), getNumKeys()))
            return false;

        return true;
    }

    protected <T> boolean arrayEquals(T[] first, T[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if ((first[i] != second[i]) && (first[i] != null)
                    && (!first[i].equals(second[i]))) {
                return false;
            }
        }
        return true;
    }

    private boolean arrayEquals(long[] first, long[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if (!(first[i] == second[i])) {
                return false;
            }
        }
        return true;
    }

    public void changeOrder(int newOrder) {
        if (newOrder == getOrder()) {
            return;
        }
        resizeEntries(newOrder);
        order = newOrder;
    }

    protected void resizeKeys(int order) {
        long[] keys = new long[order - 1];
        int length = Math.min(order, getOrder());
        System.arraycopy(getKeys(), 0, keys, 0, length);
        setKeys(keys);
    }

    protected void resizeValues(int order) {
        long[] values = new long[order - 1];
        int length = Math.min(order, getOrder());
        System.arraycopy(getValues(), 0, values, 0, length);
        setValues(values);
    }


}