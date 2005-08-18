/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ui.actions;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.window.Window;

import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextSelection;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ISelectionStatusValidator;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;

import org.eclipse.jdt.internal.corext.codemanipulation.AddGetterSetterOperation;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.codemanipulation.GetterSetterUtil;
import org.eclipse.jdt.internal.corext.codemanipulation.IRequestQuery;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.template.java.CodeTemplateContextType;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.ui.JavaElementImageDescriptor;
import org.eclipse.jdt.ui.JavaElementLabelProvider;
import org.eclipse.jdt.ui.JavaElementLabels;
import org.eclipse.jdt.ui.JavaElementSorter;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.JavaUIMessages;
import org.eclipse.jdt.internal.ui.actions.ActionMessages;
import org.eclipse.jdt.internal.ui.actions.ActionUtil;
import org.eclipse.jdt.internal.ui.actions.SelectionConverter;
import org.eclipse.jdt.internal.ui.actions.WorkbenchRunnableAdapter;
import org.eclipse.jdt.internal.ui.dialogs.SourceActionDialog;
import org.eclipse.jdt.internal.ui.dialogs.StatusInfo;
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jdt.internal.ui.preferences.JavaPreferencesSettings;
import org.eclipse.jdt.internal.ui.util.BusyIndicatorRunnableContext;
import org.eclipse.jdt.internal.ui.util.ElementValidator;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;
import org.eclipse.jdt.internal.ui.viewsupport.JavaElementImageProvider;

/**
 * Creates getter and setter methods for a type's fields. Opens a dialog with a list of
 * fields for which a setter or getter can be generated. User is able to check or uncheck
 * items before setters or getters are generated.
 * <p>
 * Will open the parent compilation unit in a Java editor. The result is unsaved, so the
 * user can decide if the changes are acceptable.
 * <p>
 * The action is applicable to structured selections containing elements of type
 * <code>IField</code> or <code>IType</code>.
 * 
 * <p>
 * This class may be instantiated; it is not intended to be subclassed.
 * </p>
 * 
 * @since 2.0
 */
public class AddGetterSetterAction extends SelectionDispatchAction {

	private boolean fSort;

	private boolean fSynchronized;

	private boolean fFinal;

	private int fVisibility;

	private boolean fGenerateComment;

	private int fNumEntries;

	private CompilationUnitEditor fEditor;

	private static final String DIALOG_TITLE= ActionMessages.AddGetterSetterAction_error_title; 

	/**
	 * Creates a new <code>AddGetterSetterAction</code>. The action requires that the
	 * selection provided by the site's selection provider is of type <code>
	 * org.eclipse.jface.viewers.IStructuredSelection</code>.
	 * 
	 * @param site the site providing context information for this action
	 */
	public AddGetterSetterAction(IWorkbenchSite site) {
		super(site);
		setText(ActionMessages.AddGetterSetterAction_label); 
		setDescription(ActionMessages.AddGetterSetterAction_description); 
		setToolTipText(ActionMessages.AddGetterSetterAction_tooltip); 

		PlatformUI.getWorkbench().getHelpSystem().setHelp(this, IJavaHelpContextIds.GETTERSETTER_ACTION);
	}

	/**
	 * Note: This constructor is for internal use only. Clients should not call this
	 * constructor.
	 * 
	 * @param editor the compilation unit editor
	 */
	public AddGetterSetterAction(CompilationUnitEditor editor) {
		this(editor.getEditorSite());
		fEditor= editor;
		setEnabled(SelectionConverter.getInputAsCompilationUnit(editor) != null);
		fEditor.getEditorSite();
	}

	// ---- Structured Viewer -----------------------------------------------------------

	/*
	 * (non-Javadoc) Method declared on SelectionDispatchAction
	 */
	public void selectionChanged(IStructuredSelection selection) {
		try {
			setEnabled(canEnable(selection));
		} catch (JavaModelException e) {
			// http://bugs.eclipse.org/bugs/show_bug.cgi?id=19253
			if (JavaModelUtil.isExceptionToBeLogged(e))
				JavaPlugin.log(e);
			setEnabled(false);
		}
	}

	/*
	 * (non-Javadoc) Method declared on SelectionDispatchAction
	 */
	public void run(IStructuredSelection selection) {
		try {
			IField[] selectedFields= getSelectedFields(selection);
			if (canRunOn(selectedFields)) {
				run(selectedFields[0].getDeclaringType(), selectedFields, false);
				return;
			}
			Object firstElement= selection.getFirstElement();

			if (firstElement instanceof IType)
				run((IType) firstElement, new IField[0], false);
			else if (firstElement instanceof ICompilationUnit) {
				// http://bugs.eclipse.org/bugs/show_bug.cgi?id=38500
				IType type= ((ICompilationUnit) firstElement).findPrimaryType();
				// type can be null if file has a bad encoding
				if (type == null) {
					MessageDialog.openError(getShell(), 
						ActionMessages.AddGetterSetterAction_no_primary_type_title, 
						ActionMessages.AddGetterSetterAction_no_primary_type_message);
					return;
				}
				if (type.isAnnotation()) {
					MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_annotation_not_applicable); 
					return;
				} else if (type.isInterface()) {
					MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_interface_not_applicable); 
					return;
				} else
					run(((ICompilationUnit) firstElement).findPrimaryType(), new IField[0], false);
			}
		} catch (CoreException e) {
			ExceptionHandler.handle(e, getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_error_actionfailed); 
		}

	}

	private boolean canEnable(IStructuredSelection selection) throws JavaModelException {
		if (getSelectedFields(selection) != null)
			return true;

		if ((selection.size() == 1) && (selection.getFirstElement() instanceof IType)) {
			IType type= (IType) selection.getFirstElement();
			return type.getCompilationUnit() != null && !type.isInterface() && !type.isLocal();
		}

		if ((selection.size() == 1) && (selection.getFirstElement() instanceof ICompilationUnit))
			return true;

		return false;
	}

	private boolean canRunOn(IField[] fields) throws JavaModelException {
		if (fields == null || fields.length == 0)
			return false;
		int count= 0;
		for (int index= 0; index < fields.length; index++) {
			if (!JdtFlags.isEnum(fields[index]))
				count++;
		}
		if (count == 0)
			MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_not_applicable); 
		return (count > 0);
	}

	private void resetNumEntries() {
		fNumEntries= 0;
	}

	private void incNumEntries() {
		fNumEntries++;
	}

	private void run(IType type, IField[] preselected, boolean editor) throws CoreException {
		if (type.isAnnotation()) {
			MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_annotation_not_applicable); 
			return;
		} else if (type.isInterface()) {
			MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_interface_not_applicable); 
			return;
		}
		if (!ElementValidator.check(type, getShell(), DIALOG_TITLE, editor))
			return;
		if (!ActionUtil.isProcessable(getShell(), type))
			return;

		ILabelProvider lp= new AddGetterSetterLabelProvider();
		resetNumEntries();
		Map entries= createGetterSetterMapping(type);
		if (entries.isEmpty()) {
			MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGettSetterAction_typeContainsNoFields_message); 
			return;
		}
		AddGetterSetterContentProvider cp= new AddGetterSetterContentProvider(entries);
		GetterSetterTreeSelectionDialog dialog= new GetterSetterTreeSelectionDialog(getShell(), lp, cp, fEditor, type);
		dialog.setSorter(new JavaElementSorter());
		dialog.setTitle(DIALOG_TITLE);
		String message= ActionMessages.AddGetterSetterAction_dialog_label;
		dialog.setMessage(message);
		dialog.setValidator(createValidator(fNumEntries));
		dialog.setContainerMode(true);
		dialog.setSize(60, 18);
		dialog.setInput(type);

		if (preselected.length > 0) {
			dialog.setInitialSelections(preselected);
			dialog.setExpandedElements(preselected);
		}
		int dialogResult= dialog.open();
		if (dialogResult == Window.OK) {
			Object[] result= dialog.getResult();
			if (result == null)
				return;
			fSort= dialog.getSortOrder();
			fSynchronized= dialog.getSynchronized();
			fFinal= dialog.getFinal();
			fVisibility= dialog.getVisibilityModifier();
			fGenerateComment= dialog.getGenerateComment();
			IField[] getterFields, setterFields, getterSetterFields;
			if (fSort) {
				getterFields= getGetterFields(result);
				setterFields= getSetterFields(result);
				getterSetterFields= new IField[0];
			} else {
				getterFields= getGetterOnlyFields(result);
				setterFields= getSetterOnlyFields(result);
				getterSetterFields= getGetterSetterFields(result);
			}
			generate(type, getterFields, setterFields, getterSetterFields, new RefactoringASTParser(AST.JLS3).parse(type.getCompilationUnit(), true), dialog.getElementPosition());
		}
	}

	private static class AddGetterSetterSelectionStatusValidator implements ISelectionStatusValidator {

		private static int fEntries;

		AddGetterSetterSelectionStatusValidator(int entries) {
			fEntries= entries;
		}

		public IStatus validate(Object[] selection) {
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=38478
			HashSet map= null;
			if ((selection != null) && (selection.length > 1)) {
				map= new HashSet(selection.length);
			}

			int count= 0;
			for (int i= 0; i < selection.length; i++) {
				try {
					if (selection[i] instanceof GetterSetterEntry) {
						Object key= selection[i];
						IField getsetField= ((GetterSetterEntry) selection[i]).fField;
						if (((GetterSetterEntry) selection[i]).fGetterEntry) {
							if (!map.add(GetterSetterUtil.getGetterName(getsetField, null)))
								return new StatusInfo(IStatus.WARNING, ActionMessages.AddGetterSetterAction_error_duplicate_methods); 
						} else {
							key= createSignatureKey(GetterSetterUtil.getSetterName(getsetField, null), getsetField);
							if (!map.add(key))
								return new StatusInfo(IStatus.WARNING, ActionMessages.AddGetterSetterAction_error_duplicate_methods); 
						}
						count++;
					}
				} catch (JavaModelException e) {
				}
			}

			if (count == 0)
				return new StatusInfo(IStatus.ERROR, ""); //$NON-NLS-1$
			String message= Messages.format(ActionMessages.AddGetterSetterAction_methods_selected, 
					new Object[] { String.valueOf(count), String.valueOf(fEntries)});
			return new StatusInfo(IStatus.INFO, message);
		}
	}

	/**
	 * Creates a key used in hash maps for a method signature
	 * (gettersettername+arguments(fqn)).
	 */
	private static String createSignatureKey(String methodName, IField field) throws JavaModelException {
		StringBuffer buffer= new StringBuffer();
		buffer.append(methodName);
		String fieldType= field.getTypeSignature();
		String signature= Signature.getSimpleName(Signature.toString(fieldType));
		buffer.append("#"); //$NON-NLS-1$
		buffer.append(signature);

		return buffer.toString();
	}

	private static ISelectionStatusValidator createValidator(int entries) {
		AddGetterSetterSelectionStatusValidator validator= new AddGetterSetterSelectionStatusValidator(entries);
		return validator;
	}

	// returns a list of fields with setter entries checked
	private static IField[] getSetterFields(Object[] result) {
		Collection list= new ArrayList(0);
		Object each= null;
		GetterSetterEntry entry= null;
		for (int i= 0; i < result.length; i++) {
			each= result[i];
			if ((each instanceof GetterSetterEntry)) {
				entry= (GetterSetterEntry) each;
				if (!entry.fGetterEntry) {
					list.add(entry.fField);
				}
			}
		}
		return (IField[]) list.toArray(new IField[list.size()]);
	}

	// returns a list of fields with getter entries checked
	private static IField[] getGetterFields(Object[] result) {
		Collection list= new ArrayList(0);
		Object each= null;
		GetterSetterEntry entry= null;
		for (int i= 0; i < result.length; i++) {
			each= result[i];
			if ((each instanceof GetterSetterEntry)) {
				entry= (GetterSetterEntry) each;
				if (entry.fGetterEntry) {
					list.add(entry.fField);
				}
			}
		}
		return (IField[]) list.toArray(new IField[list.size()]);
	}

	// returns a list of fields with only getter entries checked
	private static IField[] getGetterOnlyFields(Object[] result) {
		Collection list= new ArrayList(0);
		Object each= null;
		GetterSetterEntry entry= null;
		boolean getterSet= false;
		for (int i= 0; i < result.length; i++) {
			each= result[i];
			if ((each instanceof GetterSetterEntry)) {
				entry= (GetterSetterEntry) each;
				if (entry.fGetterEntry) {
					list.add(entry.fField);
					getterSet= true;
				}
				if ((!entry.fGetterEntry) && (getterSet == true)) {
					list.remove(entry.fField);
					getterSet= false;
				}
			} else
				getterSet= false;
		}
		return (IField[]) list.toArray(new IField[list.size()]);
	}

	// returns a list of fields with only setter entries checked
	private static IField[] getSetterOnlyFields(Object[] result) {
		Collection list= new ArrayList(0);
		Object each= null;
		GetterSetterEntry entry= null;
		boolean getterSet= false;
		for (int i= 0; i < result.length; i++) {
			each= result[i];
			if ((each instanceof GetterSetterEntry)) {
				entry= (GetterSetterEntry) each;
				if (entry.fGetterEntry) {
					getterSet= true;
				}
				if ((!entry.fGetterEntry) && (getterSet != true)) {
					list.add(entry.fField);
					getterSet= false;
				}
			} else
				getterSet= false;
		}
		return (IField[]) list.toArray(new IField[list.size()]);
	}

	// returns a list of fields with both entries checked
	private static IField[] getGetterSetterFields(Object[] result) {
		Collection list= new ArrayList(0);
		Object each= null;
		GetterSetterEntry entry= null;
		boolean getterSet= false;
		for (int i= 0; i < result.length; i++) {
			each= result[i];
			if ((each instanceof GetterSetterEntry)) {
				entry= (GetterSetterEntry) each;
				if (entry.fGetterEntry) {
					getterSet= true;
				}
				if ((!entry.fGetterEntry) && (getterSet == true)) {
					list.add(entry.fField);
					getterSet= false;
				}
			} else
				getterSet= false;
		}
		return (IField[]) list.toArray(new IField[list.size()]);
	}

	private void generate(IType type, IField[] getterFields, IField[] setterFields, IField[] getterSetterFields, CompilationUnit unit, IJavaElement elementPosition) throws CoreException {
		if (getterFields.length == 0 && setterFields.length == 0 && getterSetterFields.length == 0)
			return;

		ICompilationUnit cu= null;
		if (getterFields.length != 0)
			cu= getterFields[0].getCompilationUnit();
		else if (setterFields.length != 0)
			cu= setterFields[0].getCompilationUnit();
		else
			cu= getterSetterFields[0].getCompilationUnit();
		// open the editor, forces the creation of a working copy
		run(cu, type, getterFields, setterFields, getterSetterFields, EditorUtility.openInEditor(cu), unit, elementPosition);
	}

	// ---- Java Editor --------------------------------------------------------------

	/*
	 * (non-Javadoc) Method declared on SelectionDispatchAction
	 */
	public void selectionChanged(ITextSelection selection) {
	}

	/*
	 * (non-Javadoc) Method declared on SelectionDispatchAction
	 */
	public void run(ITextSelection selection) {
		try {
			if (!ActionUtil.isProcessable(getShell(), fEditor))
				return;

			IJavaElement[] elements= SelectionConverter.codeResolve(fEditor);
			if (elements.length == 1 && (elements[0] instanceof IField)) {
				IField field= (IField) elements[0];
				run(field.getDeclaringType(), new IField[] { field}, true);
				return;
			}
			IJavaElement element= SelectionConverter.getElementAtOffset(fEditor);

			if (element != null) {
				IType type= (IType) element.getAncestor(IJavaElement.TYPE);
				if (type != null) {
					if (type.getFields().length > 0) {
						run(type, new IField[0], true);
						return;
					}
				}
			}
			MessageDialog.openInformation(getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_not_applicable); 
		} catch (CoreException e) {
			ExceptionHandler.handle(e, getShell(), DIALOG_TITLE, ActionMessages.AddGetterSetterAction_error_actionfailed); 
		}
	}

	// ---- Helpers -------------------------------------------------------------------

	private void run(ICompilationUnit cu, IType type, IField[] getterFields, IField[] setterFields, IField[] getterSetterFields, IEditorPart editor, CompilationUnit unit, IJavaElement elementPosition) {
		IRewriteTarget target= (IRewriteTarget) editor.getAdapter(IRewriteTarget.class);
		if (target != null) {
			target.beginCompoundChange();
		}
		try {
			CodeGenerationSettings settings= JavaPreferencesSettings.getCodeGenerationSettings(cu.getJavaProject());
			settings.createComments= fGenerateComment;

			AddGetterSetterOperation op= new AddGetterSetterOperation(type, getterFields, setterFields, getterSetterFields, unit, skipSetterForFinalQuery(), skipReplaceQuery(), elementPosition, settings, true, false);
			setOperationStatusFields(op);

			IRunnableContext context= JavaPlugin.getActiveWorkbenchWindow();
			if (context == null) {
				context= new BusyIndicatorRunnableContext();
			}

			PlatformUI.getWorkbench().getProgressService().runInUI(context, new WorkbenchRunnableAdapter(op, op.getSchedulingRule()), op.getSchedulingRule());

		} catch (InvocationTargetException e) {
			String message= ActionMessages.AddGetterSetterAction_error_actionfailed; 
			ExceptionHandler.handle(e, getShell(), DIALOG_TITLE, message);
		} catch (InterruptedException e) {
			// operation canceled
		} finally {
			if (target != null) {
				target.endCompoundChange();
			}
		}
	}

	private void setOperationStatusFields(AddGetterSetterOperation op) {
		// Set the status fields corresponding to the visibility and modifiers set
		int flags= fVisibility;
		if (fSynchronized) {
			flags|= Flags.AccSynchronized;
		}
		if (fFinal) {
			flags|= Flags.AccFinal;
		}
		op.setSort(fSort);
		op.setVisibility(flags);
	}

	private IRequestQuery skipSetterForFinalQuery() {
		return new IRequestQuery() {

			public int doQuery(IMember field) {
				// Fix for http://dev.eclipse.org/bugs/show_bug.cgi?id=19367
				int[] returnCodes= { IRequestQuery.YES, IRequestQuery.YES_ALL, IRequestQuery.NO, IRequestQuery.CANCEL};
				String[] options= { IDialogConstants.YES_LABEL, IDialogConstants.YES_TO_ALL_LABEL, IDialogConstants.NO_LABEL, IDialogConstants.CANCEL_LABEL};
				String fieldName= JavaElementLabels.getElementLabel(field, 0);
				String formattedMessage= Messages.format(ActionMessages.AddGetterSetterAction_SkipSetterForFinalDialog_message, fieldName); 
				return showQueryDialog(formattedMessage, options, returnCodes);
			}
		};
	}

	private IRequestQuery skipReplaceQuery() {
		return new IRequestQuery() {

			public int doQuery(IMember method) {
				int[] returnCodes= { IRequestQuery.YES, IRequestQuery.NO, IRequestQuery.YES_ALL, IRequestQuery.CANCEL};
				String skipLabel= ActionMessages.AddGetterSetterAction_SkipExistingDialog_skip_label; 
				String replaceLabel= ActionMessages.AddGetterSetterAction_SkipExistingDialog_replace_label; 
				String skipAllLabel= ActionMessages.AddGetterSetterAction_SkipExistingDialog_skipAll_label; 
				String[] options= { skipLabel, replaceLabel, skipAllLabel, IDialogConstants.CANCEL_LABEL};
				String methodName= JavaElementLabels.getElementLabel(method, JavaElementLabels.M_PARAMETER_TYPES);
				String formattedMessage= Messages.format(ActionMessages.AddGetterSetterAction_SkipExistingDialog_message, methodName); 
				return showQueryDialog(formattedMessage, options, returnCodes);
			}
		};
	}

	private int showQueryDialog(final String message, final String[] buttonLabels, int[] returnCodes) {
		final Shell shell= getShell();
		if (shell == null) {
			JavaPlugin.logErrorMessage("AddGetterSetterAction.showQueryDialog: No active shell found"); //$NON-NLS-1$
			return IRequestQuery.CANCEL;
		}
		final int[] result= { Window.CANCEL};
		shell.getDisplay().syncExec(new Runnable() {

			public void run() {
				String title= ActionMessages.AddGetterSetterAction_QueryDialog_title; 
				MessageDialog dialog= new MessageDialog(shell, title, null, message, MessageDialog.QUESTION, buttonLabels, 0);
				result[0]= dialog.open();
			}
		});
		int returnVal= result[0];
		return returnVal < 0 ? IRequestQuery.CANCEL : returnCodes[returnVal];
	}

	/*
	 * Returns fields in the selection or <code>null</code> if the selection is empty or
	 * not valid.
	 */
	private IField[] getSelectedFields(IStructuredSelection selection) {
		List elements= selection.toList();
		int nElements= elements.size();
		if (nElements > 0) {
			IField[] res= new IField[nElements];
			ICompilationUnit cu= null;
			for (int i= 0; i < nElements; i++) {
				Object curr= elements.get(i);
				if (curr instanceof IField) {
					IField fld= (IField) curr;

					if (i == 0) {
						// remember the cu of the first element
						cu= fld.getCompilationUnit();
						if (cu == null) {
							return null;
						}
					} else if (!cu.equals(fld.getCompilationUnit())) {
						// all fields must be in the same CU
						return null;
					}
					try {
						final IType declaringType= fld.getDeclaringType();
						if (declaringType.isInterface())
							return null;
					} catch (JavaModelException e) {
						JavaPlugin.log(e);
						return null;
					}

					res[i]= fld;
				} else {
					return null;
				}
			}
			return res;
		}
		return null;
	}

	private static class AddGetterSetterLabelProvider extends JavaElementLabelProvider {

		AddGetterSetterLabelProvider() {
		}

		/*
		 * @see ILabelProvider#getText(Object)
		 */
		public String getText(Object element) {
			if (element instanceof GetterSetterEntry) {
				GetterSetterEntry entry= (GetterSetterEntry) element;
				try {
					if (entry.fGetterEntry) {
						return GetterSetterUtil.getGetterName(entry.fField, null) + "()"; //$NON-NLS-1$ 
					} else {
						return GetterSetterUtil.getSetterName(entry.fField, null) + '(' + Signature.getSimpleName(Signature.toString(entry.fField.getTypeSignature())) + ')';
					}
				} catch (JavaModelException e) {
					return ""; //$NON-NLS-1$
				}
			}
			return super.getText(element);
		}

		/*
		 * @see ILabelProvider#getImage(Object)
		 */
		public Image getImage(Object element) {
			if (element instanceof GetterSetterEntry) {
				int flags= 0;
				try {
					flags= ((GetterSetterEntry) element).fField.getFlags();
				} catch (JavaModelException e) {
					JavaPlugin.log(e);
				}
				ImageDescriptor desc= JavaElementImageProvider.getFieldImageDescriptor(false, Flags.AccPublic);
				int adornmentFlags= Flags.isStatic(flags) ? JavaElementImageDescriptor.STATIC : 0;
				desc= new JavaElementImageDescriptor(desc, adornmentFlags, JavaElementImageProvider.BIG_SIZE);
				return JavaPlugin.getImageDescriptorRegistry().get(desc);
			}
			return super.getImage(element);
		}
	}

	/**
	 * @return map IField -> GetterSetterEntry[]
	 */
	private Map createGetterSetterMapping(IType type) throws JavaModelException {
		IField[] fields= type.getFields();
		Map result= new HashMap();
		for (int i= 0; i < fields.length; i++) {
			if (!JdtFlags.isEnum(fields[i])) {
				List l= new ArrayList(2);
				if (GetterSetterUtil.getGetter(fields[i]) == null) {
					l.add(new GetterSetterEntry(fields[i], true));
					incNumEntries();
				}

				if (GetterSetterUtil.getSetter(fields[i]) == null) {
					l.add(new GetterSetterEntry(fields[i], false));
					incNumEntries();
				}

				if (!l.isEmpty())
					result.put(fields[i], l.toArray(new GetterSetterEntry[l.size()]));
			}
		}
		return result;
	}

	private static class AddGetterSetterContentProvider implements ITreeContentProvider {

		private static final Object[] EMPTY= new Object[0];

		private Viewer fViewer;

		private Map fGetterSetterEntries;

		public AddGetterSetterContentProvider(Map entries) {
			fGetterSetterEntries= entries;
		}

		/*
		 * @see IContentProvider#inputChanged(Viewer, Object, Object)
		 */
		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
			fViewer= viewer;
		}

		public Viewer getViewer() {
			return fViewer;
		}

		/*
		 * @see ITreeContentProvider#getChildren(Object)
		 */
		public Object[] getChildren(Object parentElement) {
			if (parentElement instanceof IField)
				return (Object[]) fGetterSetterEntries.get(parentElement);
			return EMPTY;
		}

		/*
		 * @see ITreeContentProvider#getParent(Object)
		 */
		public Object getParent(Object element) {
			if (element instanceof IMember)
				return ((IMember) element).getDeclaringType();
			if (element instanceof GetterSetterEntry)
				return ((GetterSetterEntry) element).fField;
			return null;
		}

		/*
		 * @see ITreeContentProvider#hasChildren(Object)
		 */
		public boolean hasChildren(Object element) {
			return getChildren(element).length > 0;
		}

		/*
		 * @see IStructuredContentProvider#getElements(Object)
		 */
		public Object[] getElements(Object inputElement) {
			return fGetterSetterEntries.keySet().toArray();
		}

		/*
		 * @see IContentProvider#dispose()
		 */
		public void dispose() {
			fGetterSetterEntries.clear();
			fGetterSetterEntries= null;
		}
	}

	private static class GetterSetterTreeSelectionDialog extends SourceActionDialog {

		private AddGetterSetterContentProvider fContentProvider;

		private static final int SELECT_GETTERS_ID= IDialogConstants.CLIENT_ID + 1;

		private static final int SELECT_SETTERS_ID= IDialogConstants.CLIENT_ID + 2;

		private IDialogSettings fSettings;

		private boolean fSortOrder;

		private final String SETTINGS_SECTION= "AddGetterSetterDialog"; //$NON-NLS-1$

		private final String SORT_ORDER= "SortOrdering"; //$NON-NLS-1$

		public GetterSetterTreeSelectionDialog(Shell parent, ILabelProvider labelProvider, AddGetterSetterContentProvider contentProvider, CompilationUnitEditor editor, IType type) throws JavaModelException {
			super(parent, labelProvider, contentProvider, editor, type, false);
			fContentProvider= contentProvider;

			// http://bugs.eclipse.org/bugs/show_bug.cgi?id=19253
			IDialogSettings dialogSettings= JavaPlugin.getDefault().getDialogSettings();
			fSettings= dialogSettings.getSection(SETTINGS_SECTION);
			if (fSettings == null) {
				fSettings= dialogSettings.addNewSection(SETTINGS_SECTION);
				fSettings.put(SORT_ORDER, false); 
			}

			fSortOrder= fSettings.getBoolean(SORT_ORDER);
		}

		public boolean getSortOrder() {
			return fSortOrder;
		}

		public void setSortOrder(boolean sort) {
			if (fSortOrder != sort) {
				fSortOrder= sort;
				fSettings.put(SORT_ORDER, sort);
				if (fContentProvider.fViewer != null) {
					fContentProvider.fViewer.refresh();
				}
			}
		}

		private void createGetterSetterButtons(Composite buttonComposite) {
			createButton(buttonComposite, SELECT_GETTERS_ID, ActionMessages.GetterSetterTreeSelectionDialog_select_getters, false); 
			createButton(buttonComposite, SELECT_SETTERS_ID, ActionMessages.GetterSetterTreeSelectionDialog_select_setters, false); 
		}

		protected void buttonPressed(int buttonId) {
			super.buttonPressed(buttonId);
			switch (buttonId) {
				case SELECT_GETTERS_ID: {
					getTreeViewer().setCheckedElements(getGetterSetterElements(true));
					updateOKStatus();
					break;
				}
				case SELECT_SETTERS_ID: {
					getTreeViewer().setCheckedElements(getGetterSetterElements(false));
					updateOKStatus();
					break;
				}
			}
		}

		protected Composite createInsertPositionCombo(Composite composite) {
			Composite entryComposite= super.createInsertPositionCombo(composite);
			addSortOrder(entryComposite);
			addVisibilityAndModifiersChoices(entryComposite);

			return entryComposite;
		}

		private Composite addSortOrder(Composite composite) {
			Label label= new Label(composite, SWT.NONE);
			label.setText(ActionMessages.GetterSetterTreeSelectionDialog_sort_label); 
			GridData gd= new GridData(GridData.FILL_BOTH);
			label.setLayoutData(gd);

			final Combo combo= new Combo(composite, SWT.READ_ONLY);
			combo.setItems(new String[] { ActionMessages.GetterSetterTreeSelectionDialog_alpha_pair_sort, 
					ActionMessages.GetterSetterTreeSelectionDialog_alpha_method_sort}); 
			final int methodIndex= 1; // Hard-coded. Change this if the
														// list gets more complicated.
			// http://bugs.eclipse.org/bugs/show_bug.cgi?id=38400
			int sort= getSortOrder() ? 1 : 0;
			combo.setText(combo.getItem(sort));
			gd= new GridData(GridData.FILL_BOTH);
			combo.setLayoutData(gd);
			combo.addSelectionListener(new SelectionAdapter() {

				public void widgetSelected(SelectionEvent e) {
					setSortOrder(combo.getSelectionIndex() == methodIndex);
				}
			});
			return composite;
		}

		private Object[] getGetterSetterElements(boolean isGetter) {
			Object[] allFields= fContentProvider.getElements(null);
			Set result= new HashSet();
			for (int i= 0; i < allFields.length; i++) {
				IField field= (IField) allFields[i];
				GetterSetterEntry[] entries= getEntries(field);
				for (int j= 0; j < entries.length; j++) {
					AddGetterSetterAction.GetterSetterEntry entry= entries[j];
					if (entry.fGetterEntry == isGetter)
						result.add(entry);
				}
			}
			return result.toArray();
		}

		private GetterSetterEntry[] getEntries(IField field) {
			List result= Arrays.asList(fContentProvider.getChildren(field));
			return (GetterSetterEntry[]) result.toArray(new GetterSetterEntry[result.size()]);
		}

		protected Composite createSelectionButtons(Composite composite) {
			Composite buttonComposite= super.createSelectionButtons(composite);

			GridLayout layout= new GridLayout();
			buttonComposite.setLayout(layout);

			createGetterSetterButtons(buttonComposite);

			layout.marginHeight= 0;
			layout.marginWidth= 0;
			layout.numColumns= 1;

			return buttonComposite;
		}

		/*
		 * @see org.eclipse.jdt.internal.ui.dialogs.SourceActionDialog#createLinkControl(org.eclipse.swt.widgets.Composite)
		 */
		protected Control createLinkControl(Composite composite) {
			Link link= new Link(composite, SWT.WRAP);
			link.setText(JavaUIMessages.GetterSetterMethodDialog_link_message); 
			link.addSelectionListener(new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					openCodeTempatePage(CodeTemplateContextType.GETTERCOMMENT_ID);
				}
			});
			link.setToolTipText(JavaUIMessages.GetterSetterMethodDialog_link_tooltip); 
			
			GridData gridData= new GridData(SWT.FILL, SWT.BEGINNING, true, false);
			gridData.widthHint= convertWidthInCharsToPixels(40); // only expand further if anyone else requires it
			link.setLayoutData(gridData);
			return link;
		}
	}

	private static class GetterSetterEntry {

		public final IField fField;

		public final boolean fGetterEntry;

		GetterSetterEntry(IField field, boolean isGetterEntry) {
			fField= field;
			fGetterEntry= isGetterEntry;
		}
	}
}
