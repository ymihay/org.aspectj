/*******************************************************************************
 * Copyright (c) 2012 Contributors.
 * All rights reserved.
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution and is available at
 * http://eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * 	Juan Mahillo, 
 *  Javier Pereira, 
 *  Abraham Nevada (lucierna)	 initial implementation
 *******************************************************************************/
package org.aspectj.weaver.tools.cache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import org.aspectj.weaver.Dump;
import org.aspectj.weaver.tools.Trace;
import org.aspectj.weaver.tools.TraceFactory;


public class SimpleCache {

	private static final String SAME_BYTES_STRING = "IDEM";
	private static final byte[] SAME_BYTES = SAME_BYTES_STRING.getBytes();

	private Map<String, byte[]> cacheMap;
	private boolean enabled = false;

	// cache for generated classes
	private Map<String, byte[]> generatedCache;
	private static final String GENERATED_CACHE_SUBFOLDER = "panenka.cache";
	private static final String GENERATED_CACHE_SEPARATOR = ";";
	
	public static final String IMPL_NAME = "shared";
	private static Set <String> initialitedSet;
	
	private boolean verboseCache = false;
	

	protected SimpleCache(String folder, boolean enabled, boolean verbose, boolean verboseGenerated) {
		this.enabled = enabled;
		try{
			if (enabled) {
				cacheMap = Collections.synchronizedMap(StoreableCachingMap.init(folder,verbose));
				String generatedCachePath = folder + File.separator + GENERATED_CACHE_SUBFOLDER;
				File f = new File (generatedCachePath);
				if (!f.exists()){
					f.mkdirs();
				}
				generatedCache = Collections.synchronizedMap(StoreableCachingMap.init(generatedCachePath,0,verboseGenerated));
				initialitedSet = Collections.synchronizedSet(new HashSet <String>());
				this.verboseCache = verbose;
			}
		}catch(Throwable t){
			System.err.print("Unexpected Error Creating Lucierna Cache:"+t);
			t.printStackTrace();
		}
	}

	public byte[] getAndInitialize(String classname, byte[] bytes,
			ClassLoader loader, ProtectionDomain protectionDomain) {
		if (!enabled) {
			return null;
		}
		byte[] res = get(classname, bytes, loader);

		if (Arrays.equals(SAME_BYTES, res)) {
			return bytes;
		} else {
			if (res != null) {
				initializeClass(classname, res, loader, protectionDomain);
			}
			return res;
		}

	}

	private byte[] get(String classname, byte bytes[], ClassLoader loader) {
		String key = generateKey(classname, bytes, loader);

		byte[] res = cacheMap.get(key);
		
		if (verboseCache){
			System.out.println("Getting class from cache. key: "+key+" . Found?"+ (res != null));
		}
		
		return res;
	}

	public void put(String classname, byte[] origbytes, byte[] wovenbytes, ClassLoader loader) {
		if (!enabled) {
			return;
		}

		String key = generateKey(classname, origbytes, loader);

		if (Arrays.equals(origbytes, wovenbytes)) {
			cacheMap.put(key, SAME_BYTES);
			return;
		}
		cacheMap.put(key, wovenbytes);
	}

	private String generateKey(String classname, byte[] bytes, ClassLoader  classloader) {
		CRC32 checksum = new CRC32();
		checksum.update(bytes);
		long crc = checksum.getValue();
		classname = classname.replace("/", ".");
		int classLoaderLevel = getClassLoaderLevel(classloader);
		return new String(classname + "-" + crc+"_"+classLoaderLevel);

	}
	
	private int getClassLoaderLevel(ClassLoader loader) {
		int level = 0;
		while (loader != null){
			loader = loader.getParent();
			level++;
		}
		return level;
	}

	private static class StoreableCachingMap extends HashMap {
		private String folder;
		private static final String CACHENAMEIDX = "cache.idx";
		private static final String CACHENAMEIDXTMP = "cache.tmp";
		
		private long lastStored = System.currentTimeMillis();
		private static int DEF_STORING_TIMER = 60000; //ms
		private int storingTimer;
		private boolean verbose = false;
		

		private transient Trace trace;
		private boolean renameWorking=true;
		private void initTrace(){
			trace = TraceFactory.getTraceFactory().getTrace(StoreableCachingMap.class);
		}
		
		
		private StoreableCachingMap(String folder, int storingTimer, boolean verbose){
			if (verbose){
				System.out.println("Lucierna: Creating new cache:"+folder);
			}
			this.folder = folder;
			initTrace();
			this.storingTimer = storingTimer;
			this.verbose = verbose;
			System.out.println("Lucierna: Created Simple Cache. folder:"+folder+"; storingTimer:" +storingTimer + "; verbose:"+verbose);
		}
		
		public static StoreableCachingMap init(String folder, boolean verbose) {
			return init(folder,DEF_STORING_TIMER, verbose);
			
		}
		
		public static StoreableCachingMap init(String folder, int storingTimer, boolean verbose) {
			File file = new File(folder + File.separator + CACHENAMEIDX);
			System.out.println("Lucierna: Initializing cache:"+file.getAbsolutePath()+ "; exists?:"+file.exists());
			if (file.exists()) {
				try {
					ObjectInputStream in = new ObjectInputStream(
							new FileInputStream(file));
					// Deserialize the object
					StoreableCachingMap sm = (StoreableCachingMap) in.readObject();
					sm.initTrace();
					in.close();
					System.out.println("Lucierna: Returning an existing cache");
					return sm;
				} catch (Throwable e) {
					System.err.println("Error reading Storable Cache");
					e.printStackTrace();
					
					Trace trace = TraceFactory.getTraceFactory().getTrace(StoreableCachingMap.class);
					trace.error("Error reading Storable Cache", e);
				//	Dump.dumpWithException(e);
				}
			}

			return new StoreableCachingMap(folder,storingTimer,verbose);

		}

		@Override
		public Object get(Object obj) {
			try {
				if (super.containsKey(obj)) {
					String path = (String) super.get(obj);
					if (path.equals(SAME_BYTES_STRING)) {
						return SAME_BYTES;
					}
					return readFromPath(path);
				} else {
					return null;
				}
			} catch (Throwable e) {
				
				System.err.println("Error reading cache: key"+obj.toString());
				e.printStackTrace();
				
				trace.error("Error reading cache: key"+obj.toString(),e);
				Dump.dumpWithException(e);
			}
			return null;
		}

		@Override
		public Object put(Object key, Object value) {
			if (verbose){
				System.out.println("Lucierna. Adding object to cache:" + key.toString() + "->"+ value.toString());
			}
			try {
				String path = null;
				byte[] valueBytes = (byte[]) value;
				
				if (Arrays.equals(valueBytes, SAME_BYTES)) {
					path = SAME_BYTES_STRING;
				} else {
					path = writeToPath((String) key, valueBytes);
				}
				Object result = super.put(key, path);
				storeMap();
				return result;
			} catch (Throwable e) {
				System.err.println("Error inserting in cache: key:"+key.toString() + "; value:"+value.toString());
				e.printStackTrace();
				
				
				trace.error("Error inserting in cache: key:"+key.toString() + "; value:"+value.toString(), e);
				Dump.dumpWithException(e);
			}
			return null;
		}
		
		

		public void storeMap() {
			if (renameWorking){
				renameWorking=storeMapAvance();
			}
			else{
				
				storeMapClassic();
			}
			
		
		}
		
		public void storeMapClassic() {
			long now = System.currentTimeMillis();
			if ((now - lastStored ) < storingTimer){
				return;
			}
			File file = new File(folder + File.separator + CACHENAMEIDX);;
			try {
				if (verbose){
					System.out.println("Start aspectj cache storing"+folder + File.separator + CACHENAMEIDX);
				}
				ObjectOutputStream out = new ObjectOutputStream(
						new FileOutputStream(file));
				// Deserialize the object
				out.writeObject(this);
				out.close();
				if (verbose){
					System.out.println("Stop aspectj cache storing"+folder + File.separator + CACHENAMEIDX);
				}
				lastStored = now;
			} catch (Throwable e) {
				
				System.err.println("Error storing cache; cache file:"+file.getAbsolutePath());
				e.printStackTrace();
				
				trace.error("Error storing cache; cache file:"+file.getAbsolutePath(), e);
				Dump.dumpWithException(e);
			}
		}
		
		public boolean storeMapAvance() {
			long now = System.currentTimeMillis();
			if ((now - lastStored ) < storingTimer){
				return true ;
			}
			File file = new File(folder + File.separator + CACHENAMEIDXTMP);;
			try {
				if (verbose){
					System.out.println("Start tmp aspectj cache storing"+folder + File.separator + CACHENAMEIDXTMP+"-->"+System.currentTimeMillis());
				}
				ObjectOutputStream out = new ObjectOutputStream(
						new FileOutputStream(file));
				// Deserialize the object
				out.writeObject(this);
				out.close();
				if (verbose){
					System.out.println("Stop tmp aspectj cache storing"+folder + File.separator + CACHENAMEIDXTMP+"-->"+System.currentTimeMillis());
				}
				File fileIDX = new File(folder + File.separator + CACHENAMEIDX);
				boolean result=file.renameTo(fileIDX);
				if (verbose){
					System.out.println("Stop tmp aspectj cache renaming"+folder + File.separator + CACHENAMEIDX+"-->"+System.currentTimeMillis());
				}
				if (!result){
					System.out.println("WARNING AspectJ cache rename is not working");
				}
				
				
				lastStored = now;
				return result;
				
			} catch (Throwable e) {
				
				System.err.println("Error tmp storing cache; cache file:"+file.getAbsolutePath());
				e.printStackTrace();
				
				trace.error("Error storing cache; cache file:"+file.getAbsolutePath(), e);
				Dump.dumpWithException(e);
			}
			return true;
		
		}

		private byte[] readFromPath(String fullPath) throws IOException {
			FileInputStream is = null ;
			try{
				is = new FileInputStream(fullPath);
			}
			catch (FileNotFoundException e){
				//may be caused by a generated class that has been stored in generated cache but not saved at cache folder
				System.out.println("FileNotFoundException:"+fullPath+"; The aspectj cache is corrupt. Please clean it and reboot the server. Cache path:"+this.folder );
				e.printStackTrace();
				
				trace.error("FileNotFoundException:"+fullPath+"; The aspectj cache is corrupt. Please clean it and reboot the server. Cache path:"+this.folder);
				Dump.dumpWithException(e);
				
				return null;
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();

			int nRead;
			byte[] data = new byte[16384];

			while ((nRead = is.read(data, 0, data.length)) != -1) {
				buffer.write(data, 0, nRead);
			}

			buffer.flush();
			is.close();
			return buffer.toByteArray();

		}

		private String writeToPath(String key, byte[] bytes) throws IOException {
			String fullPath = folder + File.separator + key;
			FileOutputStream fos = new FileOutputStream(fullPath);
			fos.write(bytes);
			fos.flush();
			fos.close();
			return fullPath;
		}
		
		public boolean isVerbose() {
			return verbose;
		}

	}

	private void initializeClass(String className, byte[] bytes,
			ClassLoader loader, ProtectionDomain protectionDomain) {
		String[] generatedClassesNames = getGeneratedClassesNames(className,bytes, loader);
		
//		if (verboseCache){
//			String key = generateKey(className, bytes, loader);
//			System.out.println("Generated class for "+key + " : "+generatedClassesNames);
//		}
		
		if (generatedClassesNames == null) {
			return;
		}
		for (String generatedClassName : generatedClassesNames) {

			byte[] generatedBytes = get(generatedClassName, bytes, loader);
			
			if (protectionDomain == null) {
				defineClass(loader, generatedClassName, generatedBytes);
			} else {
				defineClass(loader, generatedClassName, generatedBytes,
						protectionDomain);
			}

		}

	}

	private String[] getGeneratedClassesNames(String className, byte[] bytes, ClassLoader loader) {
		String key = generateKey(className, bytes, loader);

		byte[] readBytes = generatedCache.get(key);
		if (readBytes == null) {
			return null;
		}
		String readString = new String(readBytes);
		return readString.split(GENERATED_CACHE_SEPARATOR);
	}

	public void addGeneratedClassesNames(String parentClassName, byte[] parentBytes, String generatedClassName, ClassLoader loader) {
		if (!enabled) {
			return;
		}
		String key = generateKey(parentClassName, parentBytes, loader);

		byte[] storedBytes = generatedCache.get(key);
		
		//TODO can we remove the first condition?
		if (storedBytes == null || !initialitedSet.contains(key)) {
			generatedCache.put(key, generatedClassName.getBytes());
			initialitedSet.add(key);
		} else {
			String storedClasses = new String(storedBytes);
			storedClasses += GENERATED_CACHE_SEPARATOR + generatedClassName;
			generatedCache.put(key, storedClasses.getBytes());
		}
	}

	private Method defineClassMethod = null;
	private Method defineClassWithProtectionDomainMethod = null;

	private void defineClass(ClassLoader loader, String name, byte[] bytes) {

		Object clazz = null;

		try {
			if (defineClassMethod == null) {
				defineClassMethod = ClassLoader.class.getDeclaredMethod(
						"defineClass", new Class[] { String.class,
								bytes.getClass(), int.class, int.class });
			}
			defineClassMethod.setAccessible(true);
			clazz = defineClassMethod.invoke(loader, new Object[] { name,
					bytes, new Integer(0), new Integer(bytes.length) });
		} catch (InvocationTargetException e) {
			if (e.getTargetException() instanceof LinkageError) {
//				e.printStackTrace();removed due to RC-285. If the class is already defined is not a problem.avoid noise
			} else {
				System.out.println("define generated class failed"
						+ e.getTargetException());
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
			Dump.dumpWithException(e);
		}
	}

	private void defineClass(ClassLoader loader, String name, byte[] bytes,
			ProtectionDomain protectionDomain) {

		Object clazz = null;

		try {
			// System.out.println(">> Defining with protection domain " + name +
			// " pd=" + protectionDomain);
			if (defineClassWithProtectionDomainMethod == null) {
				defineClassWithProtectionDomainMethod = ClassLoader.class
						.getDeclaredMethod("defineClass", new Class[] {
								String.class, bytes.getClass(), int.class,
								int.class, ProtectionDomain.class });
			}
			defineClassWithProtectionDomainMethod.setAccessible(true);
			clazz = defineClassWithProtectionDomainMethod.invoke(loader,
					new Object[] { name, bytes, Integer.valueOf(0),
							new Integer(bytes.length), protectionDomain });
		} catch (InvocationTargetException e) {
			if (e.getTargetException() instanceof LinkageError) {
				e.printStackTrace();
				// is already defined (happens for X$ajcMightHaveAspect
				// interfaces since aspects are reweaved)
				// TODO maw I don't think this is OK and
			} else {
				System.out.println("define generated class failed"
						+ e.getTargetException());
				e.printStackTrace();
			}
		}catch (NullPointerException e) {
			System.out.println("NullPointerException loading class:"+name+".  Probabily caused by a corruput cache. Please clean it and reboot the server");
		} catch (Exception e) {
			e.printStackTrace();
			Dump.dumpWithException(e);
		}

	}

	public int getSize(){
		return cacheMap.size();
	}
}
