package org.zoodb.jdo.internal.client;

import java.util.LinkedList;
import java.util.List;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.JDOUserException;

import org.zoodb.jdo.internal.ISchema;
import org.zoodb.jdo.internal.Node;
import org.zoodb.jdo.internal.ZooClassDef;
import org.zoodb.jdo.internal.ZooFieldDef;
import org.zoodb.jdo.internal.client.session.ClientSessionCache;
import org.zoodb.jdo.internal.util.DatabaseLogger;
import org.zoodb.jdo.spi.PersistenceCapableImpl;

/**
 * This class maps schema data between the external Schema/ISchema classes and
 * the internal ZooClassDef class. 
 * 
 * @author Tilmann Z�schke
 *
 */
public class SchemaManager {

	private ClientSessionCache cache;
	private final List<SchemaOperation> ops = new LinkedList<SchemaOperation>();

	public SchemaManager(ClientSessionCache cache) {
		this.cache = cache;
	}
	
	public boolean isSchemaDefined(Class<?> cls, Node node) {
		return (locateClassDefinition(cls, node) != null);
	}

	/**
	 * Checks class and disk for class definition.
	 * @param cls
	 * @param node
	 * @return Class definition, may return null if no definition is found.
	 */
	private ZooClassDef locateClassDefinition(Class<?> cls, Node node) {
		ZooClassDef cs = cache.getSchema(cls, node);
		if (cs != null) {
			//return null if deleted
			if (!cs.jdoZooIsDeleted()) { //TODO load if hollow???
				return cs;
			}
			return null;
		}
		
		//first load super types
		//-> if (cls==PersCapableCls) then supClsDef = null
		ZooClassDef supClsDef = null;
		if (PersistenceCapableImpl.class != cls) {
			Class<?> sup = cls.getSuperclass();
			if (sup == Object.class) {
			    throw new JDOUserException("Class is not persistent capable: " + cls.getName());
			}
			supClsDef = locateClassDefinition(sup, node);
		} else {
			supClsDef = null;
		}
		

		DatabaseLogger.debugPrintln(1, "Cache miss for schema: " + cls.getName());
		ZooClassDef def = node.loadSchema(cls.getName(), supClsDef);
		return def;
	}

	public ISchema locateSchema(Class<?> cls, Node node) {
		ZooClassDef def = locateClassDefinition(cls, node);
		//not in cache and not on disk
		if (def == null) {
			return null;
		}
		//it should now be in the cache
		//return a unique handle, even if called multiple times. There is currently
		//no real reason, other than that it allows == comparison.
		ISchema ret = def.getApiHandle();
		if (ret == null) {
			ret = new ISchema(def, cls, node, this);
			def.setApiHandle(ret);
		}
		return ret;
	}

	public void refreshSchema(ZooClassDef def) {
		def.jdoZooGetNode().refreshSchema(def);
	} 
	
	public ISchema locateSchema(String className, Node node) {
		try {
			Class<?> cls = Class.forName(className);
			return locateSchema(cls, node);
		} catch (ClassNotFoundException e) {
			throw new JDOUserException("Class not found: " + className, e);
		}
	}

	public ISchema createSchema(Node node, Class<?> cls) {
		if (isSchemaDefined(cls, node)) {
			throw new JDOUserException(
					"Schema is already defined: " + cls.getName());
		}
		//Is this PersistentCapanbleImpl or a sub class?
		if (! (PersistenceCapableImpl.class.isAssignableFrom(cls))) {
//???TODO?? -> what is that for??
//			//super class in not PersistentCapableImpl. Check if it is at least 
//			//persistent capable.
//			if (!isSchemaDefined(clsSuper, node)) {
				throw new JDOUserException(
						"Class has no persistent capable super class: " + cls.getName());
//			}
		}
		ZooClassDef def;
		long oid = node.getOidBuffer().allocateOid();
		if (cls != PersistenceCapableImpl.class) {
			Class<?> clsSuper = cls.getSuperclass();
			ZooClassDef defSuper = locateClassDefinition(clsSuper, node);
			def = ZooClassDef.createFromJavaType(cls, oid, defSuper, node, cache.getSession()); 
		} else {
			def = ZooClassDef.createFromJavaType(cls, oid, null, node, cache.getSession());
		}
		cache.addSchema(def, false, node);
		ops.add(new SchemaOperation.SchemaDefine(node, def));
		return new ISchema(def, cls, node, this);
	}

	public void deleteSchema(ISchema iSchema) {
		System.out.println("FIXME SchemaManager.deleteSchema(): check fur sub-classes.");
		Node node = iSchema.getNode();
		ZooClassDef cs = iSchema.getSchemaDef();
		if (cs.jdoZooIsDeleted()) {
			throw new JDOObjectNotFoundException("This objects has already been deleted.");
		}
		//delete instances
		for( PersistenceCapableImpl pci: cache.getAllObjects()) {
			if (pci.jdoZooGetClassDef() == cs) {
				pci.jdoZooMarkDeleted();
			}
		}
		dropInstances(node, cs);
		cs.jdoZooMarkDeleted();
		ops.add(new SchemaOperation.SchemaDelete(node, iSchema.getSchemaDef()));
	}

	public void defineIndex(String fieldName, boolean isUnique, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (f.isIndexed()) {
			throw new JDOUserException("Field is already indexed: " + fieldName);
		}
		ops.add(new SchemaOperation.IndexCreate(node, f, isUnique));
	}

	public boolean removeIndex(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (!f.isIndexed()) {
			return false;
		}
		ops.add(new SchemaOperation.IndexRemove(node, f));
		return true;
	}

	public boolean isIndexDefined(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		return f.isIndexed();
	}

	public boolean isIndexUnique(String fieldName, Node node, ZooClassDef def) {
		ZooFieldDef f = getFieldDef(def, fieldName);
		if (!f.isIndexed()) {
			throw new JDOUserException("Field has no index: " + fieldName);
		}
		return f.isIndexUnique();
	}
	
	private ZooFieldDef getFieldDef(ZooClassDef def, String fieldName) {
		for (ZooFieldDef f: def.getAllFields()) {
			if (f.getName().equals(fieldName)) {
				return f;
			}
		}
		throw new JDOUserException("Field name not found: " + fieldName + " in " + 
				def.getClassName());
	}

	public void commit() {
		// perform pending operations
		for (SchemaOperation op: ops) {
			op.commit();
		}
		ops.clear();
	}

	public void rollback() {
		// undo pending operations
		for (SchemaOperation op: ops) {
			op.rollback();
		}
		ops.clear();
	}

	public Object dropInstances(Node node, ZooClassDef def) {
		ops.add(new SchemaOperation.DropInstances(node, def));
		return true;
	}
}
