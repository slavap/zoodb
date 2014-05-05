package org.zoodb.internal.server.index.btree;

import java.util.HashMap;
import java.util.Map;
import java.util.Observable;

public class BTreeMemoryBufferManager implements BTreeBufferManager {
	private Map<Integer, PagedBTreeNode> map;
	private int pageId;
	private int pageSize;

	public BTreeMemoryBufferManager() {
		this.map = new HashMap<Integer, PagedBTreeNode>();
		this.pageId = 0;
		this.pageSize = 256;
	}
	
	public BTreeMemoryBufferManager(int pageSize) {
		this.map = new HashMap<Integer, PagedBTreeNode>();
		this.pageId = 0;
		this.pageSize = pageSize;
	}
	
	@Override
	public PagedBTreeNode read(int pageId) {
		return map.get(pageId);
	}

	@Override
	public int write(PagedBTreeNode node) {
		pageId++;
		map.put(pageId, node);
		return pageId;
	}

	@Override
	public int save(PagedBTreeNode node) {
		pageId++;
		map.put(pageId, node);
		return pageId;
	}

	@Override
	public void remove(int id) {
		map.remove(id);
		return; 
	}

	@Override
	public void clear() {
		pageId = 0;
		map.clear();
	}
	@Override
	public void update(Observable o, Object arg) {
		return;
	}

	@Override
	public int getPageSize() {
		return this.pageSize;
	}

}
