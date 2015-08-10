package org.solrmarc.solr;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;

public class SolrServerProxy implements SolrProxy
{
    SolrServer solrserver;
    Object coreContainerObject = null;
    
    public SolrServerProxy(SolrServer solrserver)
    {
        this.solrserver = solrserver;
    }
    
    public SolrServerProxy(SolrServer solrserver, Object coreContainerObject)
    {
        this.solrserver = solrserver;
        this.coreContainerObject = coreContainerObject;
    }
    
    public String addDoc(Map<String, Object> fieldsMap, boolean verbose, boolean addDocToIndex) throws IOException
    {
        SolrInputDocument inputDoc = new SolrInputDocument();
        Iterator<String> keys = fieldsMap.keySet().iterator();
        while (keys.hasNext())
        {
            String fldName = keys.next();
            Object fldValObject = fieldsMap.get(fldName);
            if (fldValObject instanceof Collection<?>)
            {
                Collection<?> collValObject = (Collection<?>)fldValObject;
                for (Object item : collValObject)
                {
                    inputDoc.addField(fldName, item, 1.0f );
                }
            }
            else if (fldValObject instanceof String)
            {
                inputDoc.addField(fldName, fldValObject, 1.0f );
            }
        }
        if (addDocToIndex)
        {
            try
            {
//                System.err.println("Trying add doc in proxy: " + inputDoc.toString().replaceAll("> ", "> \n"));
                solrserver.add(inputDoc);
            }
            catch (SolrServerException e)
            {
//                System.err.println("Got SolrServerException in SolrServerProxy:addDoc");
//                e.printStackTrace(System.err);
                throw(new SolrRuntimeException("SolrserverException", e));
            }
            catch (Exception ex) {
//                System.err.println("Got Exception in SolrServerProxy:addDoc");
//                ex.printStackTrace(System.err);
            }
        }

        if (verbose || !addDocToIndex)
            return inputDoc.toString().replaceAll("> ", "> \n");
        else
            return(null);
    }

    public void close()
    {
        if (coreContainerObject != null)
        {
            try
            {
                Class<?> coreContainerClass = Class.forName("org.apache.solr.core.CoreContainer");
                Method shutdownMethod = coreContainerClass.getMethod("shutdown", (Class[])null);
                shutdownMethod.invoke(coreContainerObject, (Object[])null);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
            catch (SecurityException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
            catch (NoSuchMethodException e)
            {
               e.printStackTrace();
                System.exit(1);
            }
            catch (IllegalArgumentException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
            catch (IllegalAccessException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
            catch (InvocationTargetException e)
            {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    public SolrServer getSolrServer()
    {
        return(solrserver);        
    }
    
    public void commit(boolean optimize) throws IOException
    {
        try
        {  
            if (optimize)
                solrserver.optimize();
            else
                solrserver.commit();
        }
        catch (SolrServerException e)
        {
            throw(new SolrRuntimeException("SolrserverException", e));
        }

    }

    public void delete(String id, boolean fromCommitted, boolean fromPending) throws IOException
    {
        try
        {
            solrserver.deleteById(id);
        }
        catch (SolrServerException e)
        {
            throw(new SolrRuntimeException("SolrserverException", e));
        }
    }

    public void deleteAllDocs() throws IOException
    {
        try
        {
            solrserver.deleteByQuery("*:*");
        }
        catch (SolrServerException e)
        {
            throw(new SolrRuntimeException("SolrserverException", e));
        }
    }

    public boolean isSolrException(Exception e)
    {
        if (e.getCause() instanceof SolrServerException)
            return(true);
        return false;
    }

}
