/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.core.refactoring.rename;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.ISearchPattern;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.refactoring.Assert;
import org.eclipse.jdt.internal.core.refactoring.Checks;
import org.eclipse.jdt.internal.core.refactoring.CompositeChange;
import org.eclipse.jdt.internal.core.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.core.refactoring.RefactoringSearchEngine;
import org.eclipse.jdt.internal.core.refactoring.SearchResult;
import org.eclipse.jdt.internal.core.refactoring.SearchResultGroup;
import org.eclipse.jdt.internal.core.refactoring.base.IChange;
import org.eclipse.jdt.internal.core.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.core.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.core.refactoring.changes.RenamePackageChange;
import org.eclipse.jdt.internal.core.refactoring.tagging.IRenameRefactoring;
import org.eclipse.jdt.internal.core.refactoring.text.ITextBufferChange;
import org.eclipse.jdt.internal.core.refactoring.text.ITextBufferChangeCreator;
import org.eclipse.jdt.internal.core.refactoring.text.SimpleReplaceTextChange;


public class RenamePackageRefactoring extends Refactoring implements IRenameRefactoring{
	
	private IPackageFragment fPackage;
	private String fNewName;
	private SearchResultGroup[] fOccurrences;
	private ITextBufferChangeCreator fTextBufferChangeCreator;
	private boolean fUpdateReferences;
	
	public RenamePackageRefactoring(ITextBufferChangeCreator changeCreator, IPackageFragment pack){
		Assert.isNotNull(pack);
		Assert.isNotNull(changeCreator);
		fTextBufferChangeCreator= changeCreator;		
		fPackage= pack;
		fUpdateReferences= true;
	}
	
	/* non java-doc
	 * @see IRefactoring#getName
	 */
	public String getName(){
		return RefactoringCoreMessages.getFormattedString("RenamePackageRefactoring.name",  //$NON-NLS-1$
						new String[]{fPackage.getElementName(), fNewName});
	}
		/* non java-doc
	 * @see IRenameRefactoring#setNewName
	 */	
	public final void setNewName(String newName){
		Assert.isNotNull(newName);
		fNewName= newName;
	}
	
	/* non java-doc
	 * @see IRenameRefactoring#getCurrentName
	 */
	public final String getCurrentName(){
		return fPackage.getElementName();
	}
	
	/* non java-doc
	 * @see IRenameRefactoring#getNewName
	 */	
	public final String getNewName(){
		return fNewName;
	}
	
	/* non java-doc
	 * @see IRenameRefactoring#canUpdateReferences()
	 */
	public boolean canEnableUpdateReferences() {
		return true;
	}

	/* non java-doc
	 * @see IRenameRefactoring#setUpdateReferences(boolean)
	 */
	public void setUpdateReferences(boolean update) {
		fUpdateReferences= update;
	}	
	
	/* non java-doc
	 * @see IRenameRefactoring#getUpdateReferences()
	 */	
	public boolean getUpdateReferences(){
		return fUpdateReferences;
	}
	
	//--- preconditions
	
	public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException{
		pm.beginTask("", 1); //$NON-NLS-1$
		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkAvailability(fPackage));
		
		if (fPackage.isDefaultPackage())
			result.addFatalError(""); //$NON-NLS-1$
		pm.done();	
		return result;
	}
	
	public RefactoringStatus checkNewName() throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		result.merge(Checks.checkPackageName(fNewName));
		if (Checks.isAlreadyNamed(fPackage, fNewName))
			result.addFatalError(RefactoringCoreMessages.getString("RenamePackageRefactoring.another_name")); //$NON-NLS-1$
		result.merge(checkPackageInCurrentRoot());
		return result;
	}
	
	public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException{
		try{
			pm.beginTask("", 15); //$NON-NLS-1$
			pm.subTask(RefactoringCoreMessages.getString("RenamePackageRefactoring.checking")); //$NON-NLS-1$
			RefactoringStatus result= new RefactoringStatus();
			result.merge(checkNewName());
			pm.worked(1);
			result.merge(checkUnsavedCus());
			if (result.hasFatalError())
				return result;
			pm.worked(1);
			result.merge(checkForNativeMethods());
			pm.worked(1);
			result.merge(checkForMainMethods());
			pm.worked(1);
			
			if (!fUpdateReferences)
				return result;
				
			result.merge(Checks.checkAffectedResourcesAvailability(getOccurrences(new SubProgressMonitor(pm, 6))));
			pm.subTask(RefactoringCoreMessages.getString("RenamePackageRefactoring.analyzing")); //$NON-NLS-1$
			result.merge(analyzeAffectedCompilationUnits(new SubProgressMonitor(pm, 3)));
			result.merge(checkPackageName());
			pm.worked(1);
			return result;
		} finally{
			pm.done();
		}	
	}
	
	private IJavaSearchScope createRefactoringScope()  throws JavaModelException{
		return RefactoringScope.create(fPackage);
	}
	
	private ISearchPattern createSearchPattern(){
		return SearchEngine.createSearchPattern(fPackage, IJavaSearchConstants.REFERENCES);
	}
	
	private SearchResultGroup[] getOccurrences(IProgressMonitor pm) throws JavaModelException{
		pm.subTask(RefactoringCoreMessages.getString("RenamePackageRefactoring.searching"));	 //$NON-NLS-1$
		fOccurrences= RefactoringSearchEngine.search(pm, createRefactoringScope(), createSearchPattern());
		return fOccurrences;
	}
	
	private RefactoringStatus checkUnsavedCus() throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		IFile[] unsavedFiles= getUnsavedFiles();
		for (int i= 0; i < unsavedFiles.length; i++){
			ICompilationUnit cu= (ICompilationUnit)JavaCore.create(unsavedFiles[i]);
			if (cu != null && cu.getParent().equals(fPackage))
				result.addFatalError("Compilation unit \"" 
				+ Refactoring.getResource(cu).getProjectRelativePath()
				+ "\" must be saved before this refactoring can be performed.");
		}
		return result;
	}
	
	private RefactoringStatus checkForMainMethods() throws JavaModelException{
		ICompilationUnit[] cus= fPackage.getCompilationUnits();
		RefactoringStatus result= new RefactoringStatus();
		for (int i= 0; i < cus.length; i++)
			result.merge(Checks.checkForMainMethods(cus[i]));
		return result;
	}
	
	private RefactoringStatus checkForNativeMethods() throws JavaModelException{
		ICompilationUnit[] cus= fPackage.getCompilationUnits();
		RefactoringStatus result= new RefactoringStatus();
		for (int i= 0; i < cus.length; i++)
			result.merge(Checks.checkForNativeMethods(cus[i]));
		return result;
	}
	
	/*
	 * returns true if the new name is ok if the specified root.
	 * if a package fragment with this name exists and has java resources,
	 * then the name is not ok.
	 */
	private boolean isPackageNameOkInRoot(IPackageFragmentRoot root) throws JavaModelException{
		IPackageFragment pack= root.getPackageFragment(fNewName);
		if (! pack.exists())
			return true;
		else if (! pack.hasSubpackages()) //leaves are no good
			return false;			
		else if (pack.containsJavaResources())
			return false;
		else if (pack.getNonJavaResources().length != 0)
			return false;
		else 
			return true;	
	}
	
	private RefactoringStatus checkPackageInCurrentRoot() throws JavaModelException{
		if (isPackageNameOkInRoot(((IPackageFragmentRoot)fPackage.getParent())))
			return null;
		else
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.getString("RenamePackageRefactoring.package_exists"));//$NON-NLS-1$
	}	
	private RefactoringStatus checkPackageName() throws JavaModelException{		
		IPackageFragmentRoot[] roots= fPackage.getJavaProject().getPackageFragmentRoots();
		for (int i= 0; i < roots.length; i++) {
			if (! isPackageNameOkInRoot(roots[i]))
				return RefactoringStatus.createErrorStatus(RefactoringCoreMessages.getFormattedString("RenamePackageRefactoring.aleady_exists", fNewName));//$NON-NLS-1$
		}
		return new RefactoringStatus();
	}
		
	//-------------- AST visitor-based analysis
	
	/*
	 * Analyzes all compilation units in which type is referenced
	 */
	private RefactoringStatus analyzeAffectedCompilationUnits(IProgressMonitor pm) throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		fOccurrences= Checks.excludeCompilationUnits(fOccurrences, getUnsavedFiles(), result);
		if (result.hasFatalError())
			return result;
		
		result.merge(Checks.checkCompileErrorsInAffectedFiles(fOccurrences));	
		
		pm.beginTask("", fOccurrences.length);	 //$NON-NLS-1$
		RenamePackageASTAnalyzer analyzer= new RenamePackageASTAnalyzer(fNewName);
		for (int i= 0; i < fOccurrences.length; i++){
			if (pm.isCanceled())
				throw new OperationCanceledException();
			
			analyzeCompilationUnit(pm, analyzer, fOccurrences[i], result);
			pm.worked(1);
		}
		return result;
	}
	
	private void analyzeCompilationUnit(IProgressMonitor pm, RenamePackageASTAnalyzer analyzer, SearchResultGroup searchResults, RefactoringStatus result)  throws JavaModelException {
		CompilationUnit cu= (CompilationUnit) (JavaCore.create(searchResults.getResource()));
		pm.subTask(RefactoringCoreMessages.getFormattedString("RenamePackageRefactoring.analyzing_formatted", cu.getElementName())); //$NON-NLS-1$
		if ((! cu.exists()) || (cu.isReadOnly()) || (!cu.isStructureKnown()))
			return;
		result.merge(analyzer.analyze(searchResults.getSearchResults(), cu));
	}
	
	// ----------- Changes ---------------
	
	public IChange createChange(IProgressMonitor pm) throws JavaModelException{
		try{
			pm.beginTask(RefactoringCoreMessages.getString("RenamePackageRefactoring.creating_change"), 4); //$NON-NLS-1$
			CompositeChange builder= new CompositeChange();
	
			if (fUpdateReferences)
				addOccurrences(new SubProgressMonitor(pm, 3), builder);
	
			builder.addChange(new RenamePackageChange(fPackage, fNewName));
			pm.worked(1);
			return builder;
		} finally{
			pm.done();
		}	
	}
	
	private SimpleReplaceTextChange createTextChange(SearchResult searchResult) {
		return new SimpleReplaceTextChange(RefactoringCoreMessages.getString("RenamePackageRefactoring.update_reference"), searchResult.getStart(), searchResult.getEnd() - searchResult.getStart(), fNewName); //$NON-NLS-1$
	}
	
	private void addOccurrences(IProgressMonitor pm, CompositeChange builder) throws JavaModelException{
		pm.beginTask("", fOccurrences.length);
		for (int i= 0; i < fOccurrences.length; i++){
			IJavaElement element= JavaCore.create(fOccurrences[i].getResource());
			if (!(element instanceof ICompilationUnit))
				continue;
			ITextBufferChange change= fTextBufferChangeCreator.create(RefactoringCoreMessages.getFormattedString("RenamePackageRefactoring.update_references_to", fPackage.getElementName()), (ICompilationUnit)element); //$NON-NLS-1$
			SearchResult[] results= fOccurrences[i].getSearchResults();
			for (int j= 0; j < results.length; j++){
				change.addSimpleTextChange(createTextChange(results[j]));
			}
			builder.addChange(change);
			pm.worked(1);
		}
	}
	
}