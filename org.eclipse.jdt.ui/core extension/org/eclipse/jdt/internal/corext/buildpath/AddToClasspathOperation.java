/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.internal.corext.buildpath;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.core.resources.IFolder;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;


/**
 * Operation to add an object (of type <code>IFolder</code> or <code>
 * IJavaElement</code> to the classpath.
 * 
 * @see org.eclipse.jdt.internal.corext.buildpath.ClasspathModifier#addToClasspath(IFolder, IJavaProject, IOutputFolderQuery, IProgressMonitor)
 * @see org.eclipse.jdt.internal.corext.buildpath.ClasspathModifier#addToClasspath(IJavaElement, IJavaProject, IOutputFolderQuery, IProgressMonitor)
 * @see org.eclipse.jdt.internal.corext.buildpath.RemoveFromClasspathOperation
 */
public class AddToClasspathOperation extends ClasspathModifierOperation {
    
    /**
     * Constructor
     * 
     * @param listener a <code>IClasspathModifierListener</code> that is notified about 
     * changes on classpath entries or <code>null</code> if no such notification is 
     * necessary.
     * @param informationProvider a provider to offer information to the action
     * 
     * @see IClasspathInformationProvider
     * @see ClasspathModifier
     */
    public AddToClasspathOperation(IClasspathModifierListener listener, IClasspathInformationProvider informationProvider) {
        super(listener, informationProvider);
    }
    
    public void run(IProgressMonitor monitor) throws InvocationTargetException {
        Object result= null;
        IPath oldOutputLocation= null;
        try {
            Object element= fInformationProvider.getSelection();
            IJavaProject project= fInformationProvider.getJavaProject();
            oldOutputLocation= project.getOutputLocation();
            IOutputFolderQuery query= fInformationProvider.getOutputFolderQuery();
            if (element instanceof IFolder)
                result= addToClasspath((IFolder)element, project, query, monitor);
            else
                result= addToClasspath((IJavaElement)element, project, query, monitor);
        } catch (CoreException e) {
            fException= e;
            result= null;
        }
        
        super.handleResult(result, oldOutputLocation, IClasspathInformationProvider.ADD_TO_BP, monitor);
    }
}
