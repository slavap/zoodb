package org.zoodb.profiling.api.impl;

import org.zoodb.profiling.api.IFieldAccess;

public class FieldAccessDO implements IFieldAccess {
	
	private long oid;
	private long bytes;
	
	private String uniqueTrxId;
	private String fieldName;
	
	private boolean active;
	private boolean write;
	
	public FieldAccessDO(long oid, String uniqueTrxId, String fieldName, boolean active, boolean write) {
		this.oid = oid;
		this.uniqueTrxId = uniqueTrxId;
		this.fieldName = fieldName;
		this.active = active;
		this.write = write;
	}
	

	@Override
	public long getOid() {
		return oid;
	}

	@Override
	public void setOid(long oid) {
		this.oid = oid;
	}

	@Override
	public String getUniqueTrxId() {
		return uniqueTrxId;
	}

	@Override
	public void setUniqueTrxId(String uniqueTrxId) {
		this.uniqueTrxId = uniqueTrxId;
	}

	@Override
	public String getFieldName() {
		return fieldName;
	}

	@Override
	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	@Override
	public boolean isWrite() {
		return write;
	}

	@Override
	public void setWrite(boolean write) {
		this.write = write;
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public void setActive(boolean active) {
		this.active = active;
	}

	@Override
	public long sizeInBytes() {
		return bytes;
	}

	@Override
	public void setSizeInBytes(long bytes) {
		this.bytes = bytes;

	}

}