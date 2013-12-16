package net.sf.oval.constraint;

import static net.sf.oval.Validator.getCollectionFactory;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.jdo.ObjectState;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import ch.ethz.oserb.ConstraintManager;
import ch.ethz.oserb.ConstraintManagerFactory;
import net.sf.oval.Validator;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import net.sf.oval.context.OValContext;
import net.sf.oval.exception.OValException;
import net.sf.oval.internal.util.ReflectionUtils;

public class PrimaryKeyCheck extends AbstractAnnotationCheck<PrimaryKey>{
	
	/**
	 * generated serial version uid.
	 */
	private static final long serialVersionUID = 2857169880015248488L;
	private String[] keys;
	private Map<String, Object> keySet;
	
	@Override
	public void configure(final PrimaryKey primaryKeyAnnotation)
	{
		super.configure(primaryKeyAnnotation);
		this.keys = primaryKeyAnnotation.keys();
		keySet = getCollectionFactory().createMap(keys.length);
		requireMessageVariablesRecreation();
	}
		
	@Override
	public boolean isSatisfied(Object validatedObject, Object valueToValidate, OValContext context, Validator validator) throws OValException {
		StringBuilder filter = new StringBuilder();
		String expr;
		Class<?> clazz = validatedObject.getClass();
		// setup composite key set and filter
		try{
			for(String key:keys){
				Field field = ReflectionUtils.getFieldRecursive(clazz, key);
				if(field==null)throw new RuntimeException("declared field not found!");
				field.setAccessible(true);
				keySet.put(key, field.get(validatedObject));
				if(field.getType().equals(String.class)){
					filter.append(key+"==\""+field.get(validatedObject)+"\" && ");
				}else{
					filter.append(key+"=="+field.get(validatedObject)+" && ");
				}	
			}
			expr = filter.toString();
			if(expr.endsWith(" && "))expr=expr.substring(0, expr.length()-4);
		}catch(Exception e){
			throw new RuntimeException(e.getMessage());
		}
				
		// not null
		if(keySet.containsValue(null)) return false;
		
		// unique (among db and all running transactions)
		LinkedList<ConstraintManager> cms = ConstraintManagerFactory.getConstraintManagerList();
		PersistenceManager myPM = validator.getPersistenceManager();
			
		// check db for corresponding entry
		Query query = myPM.newQuery (validatedObject.getClass(), expr);
		@SuppressWarnings("unchecked")
		List<Object> results = (List<Object>) query.execute();
		for(Object obj:results){
			// if there is an object with the same composite key which is the object to validate->return false
			if(!obj.equals(validatedObject))return false;
		}
		
		// check managed object for corresponding entry
		Map<String, Object> keySetOther = getCollectionFactory().createMap(keys.length);
		try {
			for(ConstraintManager cm:cms){
				for(Object obj:cm.getPersistenceManager().getManagedObjects(EnumSet.of(ObjectState.PERSISTENT_DIRTY, ObjectState.PERSISTENT_NEW),clazz)){
					for(String key:keys){
						// if the current object is the object to validate->skip
						if(obj.equals(validatedObject))continue;
						Field field = ReflectionUtils.getFieldRecursive(clazz, key);
						if(field==null)throw new RuntimeException("declared field not found!");
						field.setAccessible(true);
						keySetOther.put(key, field.get(obj));
					}
					if(keySet.equals(keySetOther))return false;
				}
			}
		}catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
		
		// if no corresponding object found*/
		return true;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<String, String> createMessageVariables()
	{
		final Map<String, String> messageVariables = getCollectionFactory().createMap(2);
		messageVariables.put("id", keySet.entrySet().toString());
		return messageVariables;
	}

}