package edu.stanford;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.*;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.*;
import org.xml.sax.SAXException;

import edu.stanford.StanfordIndexer.Access;

/**
 * junit4 tests for Stanford University revisions to solrmarc
 * @author Naomi Dushay
 *
 */
public class AccessTests extends AbstractStanfordVufindTest {
	
	private final String testDataFname = "onlineFormat.mrc";
	private String fldName = "accessMethod_facet";

@Before
	public final void setup() 
			throws ParserConfigurationException, IOException, SAXException 
	{
		createIxInitVars(testDataFname);
	}

@Test
	public final void testFldProperties() 
		throws ParserConfigurationException, IOException, SAXException
	{
		// facets are indexed, not stored
		assertStringFieldProperties(fldName);
		assertFieldMultiValued(fldName);
		assertFieldIndexed(fldName);
		assertFieldNotStored(fldName);
		assertEquals("accessMethod string incorrect: ", "Online", Access.ONLINE.toString());
		assertEquals("accessMethod string incorrect: ", "At the Library", Access.AT_LIBRARY.toString());
	}

// NOTE: can have multiple access types 

	/**
	 * test accessMethod_facet value of "online" based on fulltext URLs in bib
	 */
@Test
	public final void testAccessFromFulltextURL() 
			throws IOException, ParserConfigurationException, SAXException 
	{
	    String fldVal =  Access.ONLINE.toString();
		
		Set<String> docIds = new HashSet<String>();
		// 
		docIds.add("856ind2is0"); 
		docIds.add("856ind2is0Again"); 
		docIds.add("856ind2is1NotToc"); 
		docIds.add("856ind2isBlankFulltext"); 
		docIds.add("956BlankIndicators"); 
		docIds.add("956ind2is0"); 
		docIds.add("956and856TOC"); 
		docIds.add("mult856and956"); 
		docIds.add("956and856TOCand856suppl"); 
		docIds.add("7117119"); 

		assertSearchResults(fldName, fldVal, docIds);
	}
	
	/**
	 * test accessMethod_facet value of "online" based on sfx URLs in bib
	 */
@Test
	public final void testAccessFromSfxURL() 
			throws IOException, ParserConfigurationException, SAXException 
	{
        tearDown(); createIxInitVars("formatTests.mrc");
	
    	String fldVal =  Access.ONLINE.toString();

		Set<String> docIds = new HashSet<String>();
		// has SFX url in 956
		docIds.add("7117119"); 
    	
		assertSearchResults(fldName, fldVal, docIds);
	}


	/**
	 * test accessMethod_facet value when the url is that of a GSB request 
	 *  form for offsite books.
	 */
@Test
	public final void testGSBRequestUrl() 
			throws IOException, ParserConfigurationException, SAXException 
	{
		String fldVal =  "\"" + Access.AT_LIBRARY.toString() + "\"";
	
		Set<String> docIds = new HashSet<String>();
		docIds.add("123http"); 
		docIds.add("1234https"); 
		docIds.add("7423084"); 
		assertSearchResults(fldName, fldVal, docIds);
		
		String urlFldName = "url";
		assertDocHasNoField("123http", urlFldName);
		assertDocHasNoField("1234https", urlFldName);
	}


	/**
	 * test accessMethod_facet values from item library and location fields in 
	 *  bib rec 999
	 */
@Test
	public final void testAccessFrom999() 
			throws ParserConfigurationException, IOException, SAXException
	{
		tearDown();
		createIxInitVars("buildingTests.mrc");

	 	// "Online"
	 	assertSingleResult("7117119", fldName, Access.ONLINE.toString());

	 	// "At the Library"
	 	String fldVal = "\"" + Access.AT_LIBRARY.toString() + "\"";
	 	// don't want to check *all* of them ...
	 	String resultDocIds[] = searcherProxy.getDocIdsFromSearch(fldName, fldVal, docIDfname);
	// 	List<DocumentProxy> docList = getAllMatchingDocs(fldName, fldVal);
	 	String msg = fldName + " " + Access.AT_LIBRARY.toString() + ": ";
	 	// formerly "On campus"
	 	assertDocInList(resultDocIds, "115472", msg); 
	 	assertDocInList(resultDocIds, "2442876", msg); 
	 	assertDocInList(resultDocIds, "3142611", msg);
	 	// formerly "Upon request"
	 	// SAL1 & 2
	 	assertDocInList(resultDocIds, "1033119", msg);  
	 	assertDocInList(resultDocIds, "1962398", msg);  
	 	assertDocInList(resultDocIds, "2328381", msg);  
	 	assertDocInList(resultDocIds, "2913114", msg);  
	 	// SAL3
	 	assertDocInList(resultDocIds, "690002", msg);  
	 	assertDocInList(resultDocIds, "3941911", msg); 
	 	assertDocInList(resultDocIds, "7651581", msg);  
	 	assertDocInList(resultDocIds, "2214009", msg);  
	 	// SAL-NEWARK
	 	assertDocInList(resultDocIds, "804724", msg); 
	}

}
