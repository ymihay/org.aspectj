/*******************************************************************************
 * Copyright (c) 2005 Contributors.
 * All rights reserved.
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution and is available at
 * http://eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Alexandre Vasseur         initial implementation
 *******************************************************************************/
package org.aspectj.weaver.loadtime;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.UnmodifiableClassException;
import org.aspectj.weaver.loadtime.StrConsts;

/**
 * Java 1.5 preMain agent to hook in the class pre processor
 * Can be used with -javaagent:aspectjweaver.jar
 *
 * @author <a href="mailto:alex@gnilux.com">Alexandre Vasseur</a>
 */
public class Agent { 

    /**
     * The instrumentation instance
     */
    private static Instrumentation s_instrumentation;

    /**
     * The ClassFileTransformer wrapping the weaver
     */
    private static ClassFileTransformer s_transformer = new ClassPreProcessorAgentAdapter();

    // akb: "jvm agent" mean agent loaded at jvm start
    private static boolean s_isJVMAgentActive = false;
    private static boolean s_isWarAgentUnloaded = false;

    // akb: if agent started not from premain or agentmain - possible problem 
    private static boolean CheckWarAgentActive() {
    	if (s_isJVMAgentActive)
    		return false;

    	String isWarActive = System.getProperty(StrConsts.strWarAgent);

    	if (isWarActive != null){
    		if (isWarActive.contains("false") & !s_isWarAgentUnloaded) {
    			s_isWarAgentUnloaded = true;
    			DoInstrumentationPreloadedClasses();
    		}
    
    	}
    	return true && !s_isWarAgentUnloaded;
    }

    public static boolean UseOriginalByteCode() {

    	if (s_isJVMAgentActive)
    		return false;

    	return !CheckWarAgentActive();
    }

    private static void Prepare(Instrumentation instrumentation) {
    	/* Handle duplicate agents */
    	if (s_instrumentation != null) {
    		return;
    	}
        s_instrumentation = instrumentation;
        s_instrumentation.addTransformer(s_transformer, true);
    }

    /**
     * JSR-163 preMain Agent entry method
     *
     * @param options
     * @param instrumentation
     */
    public static void premain(String options, Instrumentation instrumentation) {
    	Prepare(instrumentation);
    	
		System.setProperty(StrConsts.strJvmAgent, "true");
		s_isJVMAgentActive = true;
    }

    /**
     * Returns the Instrumentation system level instance
     */
    public static Instrumentation getInstrumentation() {
        if (s_instrumentation == null) {
            throw new UnsupportedOperationException("Java 5 was not started with preMain -javaagent for AspectJ");
        }
        return s_instrumentation;
    }

    // method for java 1.6. it must be replaced for 1.5 compatible
    public static void agentmain(String options, Instrumentation instrumentation) {
    	if (s_isJVMAgentActive)
    		return;

    	Prepare(instrumentation);
    	
		System.setProperty(StrConsts.strWarAgent, "true");
		s_isWarAgentUnloaded = false;

    	DoInstrumentationPreloadedClasses();
    }
    
    private static void DoInstrumentationPreloadedClasses() {
    	if (!s_instrumentation.isRetransformClassesSupported()) {
    		// need message: retransform is not allowed for already loaded classes
    		return;
    	}

    	Class<?> [] clss = s_instrumentation.getAllLoadedClasses();
    	
    	PreloadedBackgroundWeaverThread.instrument(clss);
    }
    
    private static class PreloadedBackgroundWeaverThread extends Thread{
    	private Class<?> []  clss;
    	private static PreloadedBackgroundWeaverThread singleton = null;
    	private boolean forceStop = false;
    	
    	public static void instrument(Class<?> []  clss){
    		if(singleton !=null){
    			try {
    				singleton.forceStop();
    				singleton.join();
				} catch (InterruptedException e) {
				}
    		}
    		
    		singleton = new PreloadedBackgroundWeaverThread(clss);
    		singleton.start();
    	}
    	
    	private void forceStop() {
    		forceStop=true;
		}

		private PreloadedBackgroundWeaverThread(Class<?> []  clss){
    		this.clss=clss;
    	}
    	
    	public void run(){
    		if(s_isWarAgentUnloaded){
    			System.out.println("SMARTBEAR - Starting to deinstrument "+clss.length+ " already loaded classes. This process may take a while.");

    		}else{
    			System.out.println("SMARTBEAR - Starting to instrument "+clss.length+ " already loaded classes. This process may take a while.");
    		}
    		int counter = 0;
        	for (Class<?> cls: clss) {
        		if(forceStop){
            		System.out.println("SMARTBEAR - Current instrumentation process has been stop.");
        			break;
        		}
        		
        		counter++;
        		if(counter % 100 == 0){
            		if(s_isWarAgentUnloaded){
            			System.out.println("SMARTBEAR - Deinstrumentation progress: " + (int) (counter / (1.0*clss.length)*100 ) + "%");
            		}else{
            			System.out.println("SMARTBEAR - Instrumentation progress: " + (int) (counter / (1.0*clss.length)*100 ) + "%");
            		}
        		}
      
          		if (cls.toString().contains("com.sun.tools.attach.AgentInitializationException"))
          			continue;
          		if (cls.toString().contains("org.apache.tomcat.jni.OS"))
          			continue;

        		try {
        			s_instrumentation.retransformClasses(cls);
        		} catch (Throwable th) {
        		 // Do nothing with this kind of errors. 
    			}
        	}
    		if(s_isWarAgentUnloaded){
    			System.out.println("SMARTBEAR - Deinstrumentation completed!");
    		}else{
    			System.out.println("SMARTBEAR - Instrumentation completed!");
    		}
        	singleton=null;
    	}
    }

}