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
package org.eclipse.core.internal.registry;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import org.eclipse.core.internal.runtime.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.service.datalocation.FileManager;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.Bundle;

/**
 * An implementation for the extension registry API.
 */
public class ExtensionRegistry implements IExtensionRegistry {
	private EclipseBundleListener pluginBundleListener;

	private final static class ExtensionEventDispatcherJob extends Job {
		// an "identy rule" that forces extension events to be queued		
		private final static ISchedulingRule EXTENSION_EVENT_RULE = new ISchedulingRule() {
			public boolean contains(ISchedulingRule rule) {
				return rule == this;
			}

			public boolean isConflicting(ISchedulingRule rule) {
				return rule == this;
			}
		};
		private Map deltas;
		private Object[] listenerInfos;
		private RegistryObjectManager objectManager;

		public ExtensionEventDispatcherJob(Object[] listenerInfos, Map deltas, RegistryObjectManager objects) {
			// name not NL'd since it is a system job
			super("Registry event dispatcher"); //$NON-NLS-1$
			setSystem(true);
			this.listenerInfos = listenerInfos;
			this.deltas = deltas;
			this.objectManager = objects;
			// all extension event dispatching jobs use this rule
			setRule(EXTENSION_EVENT_RULE);
		}

		public IStatus run(IProgressMonitor monitor) {
			MultiStatus result = new MultiStatus(Platform.PI_RUNTIME, IStatus.OK, Policy.bind("plugin.eventListenerError"), null); //$NON-NLS-1$			
			for (int i = 0; i < listenerInfos.length; i++) {
				ListenerInfo listenerInfo = (ListenerInfo) listenerInfos[i];
				if (listenerInfo.filter != null && !deltas.containsKey(listenerInfo.filter))
					continue;
				try {
					listenerInfo.listener.registryChanged(new RegistryChangeEvent(deltas, listenerInfo.filter));
				} catch (RuntimeException re) {
					String message = re.getMessage() == null ? "" : re.getMessage(); //$NON-NLS-1$
					result.add(new Status(IStatus.ERROR, Platform.PI_RUNTIME, IStatus.OK, message, re));
				}
			}
			cleanupRemovedObjects();
			return result;
		}

		private Handle[] collect(ConfigurationElementHandle ce) {
			ConfigurationElementHandle[] children = (ConfigurationElementHandle[]) ce.getChildren();
			Handle[] result = new Handle[] {ce};
			for (int i = 0; i < children.length; i++) {
				result = (Handle[]) addArrays(result, collect(children[i]));
			}
			return result;
		}

		//Collect all the elements that must be removed, then actually remove them. 
		void cleanupRemovedObjects() {
			List removedExtensions = new ArrayList(); //List of ExtensionHandle
			List removedExtensionPoints = new ArrayList(); //List of extensionpoint name

			Set deltaEntries = deltas.entrySet();
			//Collect the extensions and the extension points being removed
			for (Iterator iter = deltaEntries.iterator(); iter.hasNext();) {
				Map.Entry entry = (Map.Entry) iter.next();
				RegistryDelta currentDelta = (RegistryDelta) entry.getValue();

				//First collect all the extensions being removed
				ExtensionDelta[] extensionDeltas = (ExtensionDelta[]) ((RegistryDelta) entry.getValue()).getExtensionDeltas();
				for (int i = 0; i < extensionDeltas.length; i++) {
					if (extensionDeltas[i].getKind() != IExtensionDelta.REMOVED)
						continue;
					removedExtensions.add(extensionDeltas[i].getExtension());
				}

				removedExtensionPoints.addAll(currentDelta.removedExtensionPoints());
			}

			Handle[] toBeRemoved = new Handle[0];
			//We do not need to iterate through the extensions of the extension points because 
			//the code responsible for doing the removal will have them added in various deltas. Moreover, by now the extension point no longer has extensions
			//since the links have been cut when doing the remove.

			//Now do a traversal of all the extensions that must be removed to collect the ids of all the configuration elements to remove
			for (Iterator iter = removedExtensions.iterator(); iter.hasNext();) {
				ExtensionHandle extension = (ExtensionHandle) iter.next();
				toBeRemoved = (Handle[]) addArrays(toBeRemoved, new ExtensionHandle[] {extension});
				ConfigurationElementHandle[] ces = (ConfigurationElementHandle[]) extension.getConfigurationElements();
				for (int j = 0; j < ces.length; j++) {
					toBeRemoved = (Handle[]) addArrays(toBeRemoved, collect(ces[j]));
				}
			}

			//Now actually remove the objects
			//remove the extension points
			for (Iterator iter = removedExtensionPoints.iterator(); iter.hasNext();) {
				objectManager.removeExtensionPoint((String) iter.next());
			}

			//remove all the other objects
			for (int i = 0; i < toBeRemoved.length; i++) {
				objectManager.remove((toBeRemoved[i]).getId(), true);
			}
		}
	}

	class ListenerInfo {
		String filter;
		IRegistryChangeListener listener;

		public ListenerInfo(IRegistryChangeListener listener, String filter) {
			this.listener = listener;
			this.filter = filter;
		}

		/**
		 * Used by ListenerList to ensure uniqueness.
		 */
		public boolean equals(Object another) {
			return another instanceof ListenerInfo && ((ListenerInfo) another).listener == this.listener;
		}
	}

	public static boolean DEBUG;

	private static final String OPTION_DEBUG_EVENTS_EXTENSION = "org.eclipse.core.runtime/registry/debug/events/extension"; //$NON-NLS-1$	

	// used to enforce concurrent access policy for readers/writers
	private ReadWriteMonitor access = new ReadWriteMonitor();

	// deltas not broadcasted yet
	private transient Map deltas = new HashMap(11);

	// all registry change listeners
	private transient ListenerList listeners = new ListenerList();

	// extensions without extension point
	private Map orphanExtensions = new HashMap(11);

	private RegistryObjectManager registryObjects;

	public RegistryObjectManager getObjectManager() {
		Handle.objectManager = registryObjects;
		return registryObjects;
	}

	/**
	 * Adds and resolves all extensions and extension points provided by the
	 * plug-in.
	 * <p>
	 * A corresponding IRegistryChangeEvent will be broadcast to all listeners
	 * interested on changes in the given plug-in.
	 * </p>
	 */
	public void add(Namespace element) {
		access.enterWrite();
		try {
			basicAdd(element, true);
			fireRegistryChangeEvent();
		} finally {
			access.exitWrite();
		}
	}

	public void add(Namespace[] elements) {
		access.enterWrite();
		try {
			for (int i = 0; i < elements.length; i++)
				basicAdd(elements[i], true);
			fireRegistryChangeEvent();
		} finally {
			access.exitWrite();
		}
	}

	/* Utility method to help with array concatenations */
	static Object addArrays(Object a, Object b) {
		Object[] result = (Object[]) Array.newInstance(a.getClass().getComponentType(), Array.getLength(a) + Array.getLength(b));
		System.arraycopy(a, 0, result, 0, Array.getLength(a));
		System.arraycopy(b, 0, result, Array.getLength(a), Array.getLength(b));
		return result;
	}

	private void addExtension(int extension) {
		Extension addedExtension = (Extension) registryObjects.getObject(extension, RegistryObjectManager.EXTENSION);
		String extensionPointToAddTo = addedExtension.getExtensionPointIdentifier();
		ExtensionPoint extPoint = registryObjects.getExtensionPointObject(extensionPointToAddTo);
		//orphan extension
		if (extPoint == null) {
			// are there any other orphan extensions
			int[] existingOrphanExtensions = (int[]) orphanExtensions.get(extensionPointToAddTo);
			if (existingOrphanExtensions != null) {
				// just add
				int[] newOrphanExtensions = new int[existingOrphanExtensions.length + 1];
				System.arraycopy(existingOrphanExtensions, 0, newOrphanExtensions, 0, existingOrphanExtensions.length);
				newOrphanExtensions[newOrphanExtensions.length - 1] = extension;
				orphanExtensions.put(extensionPointToAddTo, newOrphanExtensions);
			} else
				// otherwise this is the first one
				orphanExtensions.put(extensionPointToAddTo, new int[] {extension});
			return;
		}
		// otherwise, link them
		int[] newExtensions;
		int[] existingExtensions = extPoint.getRawChildren();
		if (existingExtensions == null)
			newExtensions = new int[] {extension};
		else {
			newExtensions = new int[existingExtensions.length + 1];
			System.arraycopy(existingExtensions, 0, newExtensions, 0, existingExtensions.length);
			newExtensions[newExtensions.length - 1] = extension;
		}
		link(extPoint, newExtensions);
		recordChange(extPoint, extension, IExtensionDelta.ADDED);
	}

	/**
	 * Looks for existing orphan extensions to connect to the given extension
	 * point. If none is found, there is nothing to do. Otherwise, link them.
	 */
	private void addExtensionPoint(int extPoint) {
		ExtensionPoint extensionPoint = (ExtensionPoint) registryObjects.getObject(extPoint, RegistryObjectManager.EXTENSION_POINT);
		int[] orphans = (int[]) orphanExtensions.remove(extensionPoint.getUniqueIdentifier());
		if (orphans == null)
			return;
		// otherwise, link them
		int[] existingExtensions = extensionPoint.getRawChildren();
		if (existingExtensions != null && existingExtensions.length != 0) { //TODO Verify with someone that this never happens
			System.err.println("this can not happen because this code is only being called when a new extensoin point is being added because a new plugin is being parsed"); //$NON-NLS-1$
			//			newExtensions = new IExtension[existingExtensions.length + orphans.length];
			//			System.arraycopy(existingExtensions, 0, newExtensions, 0, existingExtensions.length);
			//			System.arraycopy(orphans, 0, newExtensions, existingExtensions.length, orphans.length);
		}
		link(extensionPoint, orphans);
		recordChange(extensionPoint, orphans, IExtensionDelta.ADDED);
	}

	private void addExtensionsAndExtensionPoints(Namespace element) {
		// now add and resolve extensions and extension points
		int[] extPoints = element.getExtensionPoints();
		for (int i = 0; i < extPoints.length; i++)
			this.addExtensionPoint(extPoints[i]);
		int[] extensions = element.getExtensions();
		for (int i = 0; i < extensions.length; i++)
			this.addExtension(extensions[i]);
	}

	public void addRegistryChangeListener(IRegistryChangeListener listener) {
		// this is just a convenience API - no need to do any sync'ing here		
		addRegistryChangeListener(listener, null);
	}

	public void addRegistryChangeListener(IRegistryChangeListener listener, String filter) {
		synchronized (listeners) {
			listeners.add(new ListenerInfo(listener, filter));
		}
	}

	private void basicAdd(Namespace element, boolean link) {
		registryObjects.addNamespace(element);
		// ignore anonymous namespaces
		if (element.getUniqueIdentifier() == null)
			return;

		if (!link)
			return;

		addExtensionsAndExtensionPoints(element);
	}

	private boolean basicRemove(long bundleId) {
		// ignore anonymous namespaces
		//		Bundle removedBundle = InternalPlatform.getDefault().getBundleContext().getBundle(bundleId);
		//		if (Platform.isFragment(removedBundle))
		//			removedBundle = Platform.getHosts(removedBundle)[0];
		//		
		//		if (removedBundle.getSymbolicName() == null)
		//			return false;
		//		
		//		if (! registryObjects.getNamespaces().contains(removedBundle.getSymbolicName())) {
		//			if (DEBUG)
		//				System.out.println("********* Element unknown: " + removedBundle.getSymbolicName() + " - not removed."); //$NON-NLS-1$//$NON-NLS-2$
		//			return false;
		//		}
		removeExtensionsAndExtensionPoints(bundleId);
		registryObjects.removeNamespace(bundleId);
		return true;
	}

	// allow other objects in the registry to use the same lock
	void enterRead() {
		access.enterRead();
	}

	// allow other objects in the registry to use the same lock	
	void exitRead() {
		access.exitRead();
	}

	/**
	 * Broadcasts (asynchronously) the event to all interested parties.
	 */
	private void fireRegistryChangeEvent() {
		// if there is nothing to say, just bail out
		if (deltas.isEmpty() || listeners.isEmpty())
			return;
		// for thread safety, create tmp collections
		Object[] tmpListeners = listeners.getListeners();
		Map tmpDeltas = new HashMap(this.deltas);
		// the deltas have been saved for notification - we can clear them now
		deltas.clear();
		// do the notification asynchronously
		new ExtensionEventDispatcherJob(tmpListeners, tmpDeltas, registryObjects).schedule();
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String extensionPointId) {
		// this is just a convenience API - no need to do any sync'ing here		
		int lastdot = extensionPointId.lastIndexOf('.');
		if (lastdot == -1)
			return new IConfigurationElement[0];
		return getConfigurationElementsFor(extensionPointId.substring(0, lastdot), extensionPointId.substring(lastdot + 1));
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String, java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String extensionPointSimpleId) {
		// this is just a convenience API - no need to do any sync'ing here
		IExtensionPoint extPoint = this.getExtensionPoint(pluginId, extensionPointSimpleId);
		if (extPoint == null)
			return new IConfigurationElement[0];
		return extPoint.getConfigurationElements();
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String, java.lang.String, java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String extensionPointName, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		IExtension extension = this.getExtension(pluginId, extensionPointName, extensionId);
		if (extension == null)
			return new IConfigurationElement[0];
		return extension.getConfigurationElements();
	}

	private RegistryDelta getDelta(long bundleId) {
		// is there a delta for the plug-in?
		RegistryDelta existingDelta = (RegistryDelta) deltas.get(new Long(bundleId));
		if (existingDelta != null)
			return existingDelta;

		//if not, create one
		RegistryDelta delta = new RegistryDelta(bundleId);
		deltas.put(new Long(bundleId), delta);
		return delta;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String)
	 */
	public IExtension getExtension(String extensionId) {
		if (extensionId == null)
			return null;
		int lastdot = extensionId.lastIndexOf('.');
		if (lastdot == -1)
			return null;
		String namespace = extensionId.substring(0, lastdot);

		Bundle correspondingBundle = Platform.getBundle(namespace);
		if (correspondingBundle == null)
			return null;

		int[] extensions = registryObjects.getExtensionsFrom(correspondingBundle.getBundleId());
		for (int i = 0; i < extensions.length; i++) {
			if (extensionId.equals(((Extension) registryObjects.getObject(extensions[i], RegistryObjectManager.EXTENSION)).getUniqueIdentifier()))
				return (IExtension) registryObjects.getHandle(extensions[i], RegistryObjectManager.EXTENSION);
		}
		return null;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String, java.lang.String)
	 */
	public IExtension getExtension(String extensionPointId, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		int lastdot = extensionPointId.lastIndexOf('.');
		if (lastdot == -1)
			return null;
		return getExtension(extensionPointId.substring(0, lastdot), extensionPointId.substring(lastdot + 1), extensionId);
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String, java.lang.String, java.lang.String)
	 */
	public IExtension getExtension(String pluginId, String extensionPointName, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		IExtensionPoint extPoint = getExtensionPoint(pluginId, extensionPointName);
		if (extPoint != null)
			return extPoint.getExtension(extensionId);
		return null;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoint(java.lang.String)
	 */
	public IExtensionPoint getExtensionPoint(String xptUniqueId) {
		return registryObjects.getExtensionPointHandle(xptUniqueId);
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoint(java.lang.String, java.lang.String)
	 */
	public IExtensionPoint getExtensionPoint(String elementName, String xpt) {
		access.enterRead();
		try {
			return registryObjects.getExtensionPointHandle(elementName + '.' + xpt);
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoints()
	 */
	public IExtensionPoint[] getExtensionPoints() {
		access.enterRead();
		try {
			return registryObjects.getExtensionPointsHandles();
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoints(java.lang.String)
	 */
	public IExtensionPoint[] getExtensionPoints(String namespace) {
		access.enterRead();
		try {
			return (IExtensionPoint[]) registryObjects.getHandles(registryObjects.getExtensionPointsFrom(Platform.getBundle(namespace).getBundleId()), RegistryObjectManager.EXTENSION_POINT);
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensions(java.lang.String)
	 */
	public IExtension[] getExtensions(String namespace) {
		access.enterRead();
		try {
			Bundle correspondingBundle = Platform.getBundle(namespace);
			if (correspondingBundle == null)
				return ExtensionHandle.EMPTY_ARRAY;
			return (IExtension[]) registryObjects.getHandles(registryObjects.getExtensionsFrom(Platform.getBundle(namespace).getBundleId()), RegistryObjectManager.EXTENSION);
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getNamespaces()
	 */
	public String[] getNamespaces() {
		access.enterRead();
		try {
			Set namespaces = registryObjects.getNamespaces();
			String[] result = new String[namespaces.size()];
			return (String[]) namespaces.toArray(result);
		} finally {
			access.exitRead();
		}
	}

	boolean hasNamespace(long name) {
		access.enterRead();
		try {
			return registryObjects.hasNamespace(name);
		} finally {
			access.exitRead();
		}
	}

	private void link(ExtensionPoint extPoint, int[] extensions) {
		if (extensions == null || extensions.length == 0) {
			extPoint.setRawChildren(null);
			return;
		}
		extPoint.setRawChildren(extensions);
	}

	/*
	 * Records an extension addition/removal.
	 */
	private void recordChange(ExtensionPoint extPoint, int extension, int kind) {
		// avoid computing deltas when there are no listeners
		if (listeners.isEmpty())
			return;
		ExtensionDelta extensionDelta = new ExtensionDelta();
		extensionDelta.setExtension(extension);
		extensionDelta.setExtensionPoint(extPoint.getObjectId());
		extensionDelta.setKind(kind);
		getDelta(extPoint.getBundleId()).addExtensionDelta(extensionDelta);
	}

	/*
	 * Records a set of extension additions/removals.
	 */
	private void recordChange(ExtensionPoint extPoint, int[] extensions, int kind) {
		if (listeners.isEmpty())
			return;
		if (extensions == null || extensions.length == 0)
			return;
		RegistryDelta pluginDelta = getDelta(extPoint.getBundleId());
		for (int i = 0; i < extensions.length; i++) {
			ExtensionDelta extensionDelta = new ExtensionDelta();
			extensionDelta.setExtension(extensions[i]);
			extensionDelta.setExtensionPoint(extPoint.getObjectId());
			extensionDelta.setKind(kind);
			pluginDelta.addExtensionDelta(extensionDelta);
		}
	}

	private void recordExtensionPointRemoval(ExtensionPoint extPoint) {
		RegistryDelta pluginDelta = getDelta(extPoint.getBundleId());
		pluginDelta.addRemovedExtensionPoints(extPoint.getUniqueIdentifier());
	}

	/**
	 * Unresolves and removes all extensions and extension points provided by
	 * the plug-in.
	 * <p>
	 * A corresponding IRegistryChangeEvent will be broadcast to all listeners
	 * interested on changes in the given plug-in.
	 * </p>
	 */
	public boolean remove(long bundleId) {
		access.enterWrite();
		try {
			IRegistryChangeListener dummyChangeListener = null;
			if (listeners.isEmpty()) {
				dummyChangeListener = new IRegistryChangeListener() {
					public void registryChanged(IRegistryChangeEvent event) {
						// nothing to do
					}
				};
				addRegistryChangeListener(dummyChangeListener);
			}

			if (!basicRemove(bundleId))
				return false;
			fireRegistryChangeEvent();

			if (dummyChangeListener != null)
				removeRegistryChangeListener(dummyChangeListener);
			return true;
		} finally {
			access.exitWrite();
		}
	}

	private void removeExtension(int extensionId) {
		Extension extension = (Extension) registryObjects.getObject(extensionId, RegistryObjectManager.EXTENSION);
		String xptName = extension.getExtensionPointIdentifier();
		ExtensionPoint extPoint = registryObjects.getExtensionPointObject(xptName);
		if (extPoint == null) {
			// not found - maybe it was an orphan extension
			int[] existingOrphanExtensions = (int[]) orphanExtensions.get(xptName);
			if (existingOrphanExtensions == null)
				// nope, this extension is unknown
				return;
			// yes, so just remove it from the orphans list
			int[] newOrphanExtensions = new int[existingOrphanExtensions.length - 1];
			for (int i = 0, j = 0; i < existingOrphanExtensions.length; i++)
				if (extension.getObjectId() != existingOrphanExtensions[i])
					newOrphanExtensions[j++] = existingOrphanExtensions[i];
			orphanExtensions.put(xptName, newOrphanExtensions);
			return;
		}
		// otherwise, unlink the extension from the extension point
		int[] existingExtensions = extPoint.getRawChildren();
		int[] newExtensions = null;
		if (existingExtensions.length > 1) {
			newExtensions = new int[existingExtensions.length - 1];
			for (int i = 0, j = 0; i < existingExtensions.length; i++)
				if (existingExtensions[i] != extension.getObjectId())
					newExtensions[j++] = existingExtensions[i];
		}
		link(extPoint, newExtensions);
		recordChange(extPoint, extension.getObjectId(), IExtensionDelta.REMOVED);
	}

	private void removeExtensionPoint(int extPoint) {
		ExtensionPoint extensionPoint = (ExtensionPoint) registryObjects.getObject(extPoint, RegistryObjectManager.EXTENSION_POINT);
		int[] existingExtensions = extensionPoint.getRawChildren();
		if (existingExtensions == null || existingExtensions.length == 0) {
			return;
		}
		//Remove the extension point from the registry object
		recordExtensionPointRemoval(extensionPoint);
		orphanExtensions.put(extensionPoint.getUniqueIdentifier(), existingExtensions);
		link(extensionPoint, null);
		recordChange(extensionPoint, existingExtensions, IExtensionDelta.REMOVED);
	}

	private void removeExtensionsAndExtensionPoints(long bundleId) {
		//The removal of the actual objects is carried out when the broadcast of the delta is over see cleanupRemovedObjects
		// remove extensions
		int[] extensions = registryObjects.getExtensionsFrom(bundleId);
		for (int i = 0; i < extensions.length; i++)
			this.removeExtension(extensions[i]);

		// remove extension points
		int[] extPoints = registryObjects.getExtensionPointsFrom(bundleId);
		for (int i = 0; i < extPoints.length; i++)
			this.removeExtensionPoint(extPoints[i]);
	}

	public void removeRegistryChangeListener(IRegistryChangeListener listener) {
		synchronized (listeners) {
			listeners.remove(new ListenerInfo(listener, null));
		}
	}

	public ExtensionRegistry() {
		boolean fromCache = false;
		registryObjects = new RegistryObjectManager();
		if (!"true".equals(System.getProperty(InternalPlatform.PROP_NO_REGISTRY_CACHE))) { //$NON-NLS-1$
			// Try to read the registry from the cache first. If that fails, create a new registry
			MultiStatus problems = new MultiStatus(Platform.PI_RUNTIME, ExtensionsParser.PARSE_PROBLEM, "Registry cache problems", null); //$NON-NLS-1$

			long start = 0;
			if (InternalPlatform.DEBUG)
				start = System.currentTimeMillis();

			boolean lazyLoading = !"true".equals(System.getProperty(InternalPlatform.PROP_NO_LAZY_CACHE_LOADING)); //$NON-NLS-1$
			//			if (lazyLoading)
			//				registryObjects = new EargetRegistryObjectManager();

			//Find the cache in the local configuration area
			File cacheFile = null;
			FileManager currentFileManager = null;
			try {
				currentFileManager = InternalPlatform.getDefault().getRuntimeFileManager();
				cacheFile = currentFileManager.lookup(TableReader.TABLE, false);
			} catch (IOException e) {
				//Ignore the exception. The registry will be rebuilt from the xml files.
			}
			//Find the cache in the shared configuration area
			if (cacheFile == null || !cacheFile.isFile()) {
				Location currentLocation = Platform.getConfigurationLocation();
				Location parentLocation = null;
				if (currentLocation != null && (parentLocation = currentLocation.getParentLocation()) != null) {
					try {
						currentFileManager = new FileManager(new File(parentLocation.getURL().getFile() + '/' + Platform.PI_RUNTIME), "none"); //$NON-NLS-1$
						currentFileManager.open(false);
						cacheFile = currentFileManager.lookup(TableReader.TABLE, false);
					} catch (IOException e) {
						//Ignore the exception. The registry will be rebuilt from the xml files.
					}
				}
			}

			//The cache is made of several files, find the real names of these other files. If all files are found, try to initialize the objectManager
			if (cacheFile != null && cacheFile.isFile()) {
				TableReader.setTableFile(cacheFile);
				try {
					TableReader.setExtraDataFile(currentFileManager.lookup(TableReader.EXTRA, false));
					TableReader.setMainDataFile(currentFileManager.lookup(TableReader.MAIN, false));
					TableReader.setNamespaceFile(currentFileManager.lookup(TableReader.NAMESPACE, false));
					fromCache = registryObjects.init(computeRegistryStamp());
				} catch (IOException e) {
					// Ignore the exception. The registry will be rebuilt from the xml files.
				}
			}

			if (InternalPlatform.DEBUG && fromCache)
				System.out.println("Reading registry cache: " + (System.currentTimeMillis() - start)); //$NON-NLS-1$

			if (InternalPlatform.DEBUG_REGISTRY) {
				if (!fromCache)
					System.out.println("Reloading registry from manifest files..."); //$NON-NLS-1$
				else
					System.out.println("Using registry cache..."); //$NON-NLS-1$
			}
		}

		String debugOption = InternalPlatform.getDefault().getOption(OPTION_DEBUG_EVENTS_EXTENSION);
		DEBUG = debugOption == null ? false : debugOption.equalsIgnoreCase("true"); //$NON-NLS-1$	
		if (DEBUG)
			addRegistryChangeListener(new IRegistryChangeListener() {
				public void registryChanged(IRegistryChangeEvent event) {
					System.out.println(event);
				}
			});

		// register a listener to catch new bundle installations/resolutions.
		pluginBundleListener = new EclipseBundleListener(this);
		InternalPlatform.getDefault().getBundleContext().addBundleListener(pluginBundleListener);

		// populate the registry with all the currently installed bundles.
		// There is a small window here while processBundles is being
		// called where the pluginBundleListener may receive a BundleEvent 
		// to add/remove a bundle from the registry.  This is ok since
		// the registry is a synchronized object and will not add the
		// same bundle twice.
		if (!fromCache)
			pluginBundleListener.processBundles(InternalPlatform.getDefault().getBundleContext().getBundles());

		InternalPlatform.getDefault().getBundleContext().registerService(IExtensionRegistry.class.getName(), this, new Hashtable()); //$NON-NLS-1$

	}

	public void stop() {
		InternalPlatform.getDefault().getBundleContext().removeBundleListener(this.pluginBundleListener);
		if (!registryObjects.isDirty())
			return;
		FileManager manager = InternalPlatform.getDefault().getRuntimeFileManager();
		File tableFile = null;
		File mainFile = null;
		File extraFile = null;
		File namespaceFile = null;
		try {
			manager.lookup(TableReader.TABLE, true);
			manager.lookup(TableReader.MAIN, true);
			manager.lookup(TableReader.EXTRA, true);
			manager.lookup(TableReader.NAMESPACE, true);
			tableFile = File.createTempFile(TableReader.TABLE, ".new", manager.getBase()); //$NON-NLS-1$
			mainFile = File.createTempFile(TableReader.MAIN, ".new", manager.getBase()); //$NON-NLS-1$
			extraFile = File.createTempFile(TableReader.EXTRA, ".new", manager.getBase()); //$NON-NLS-1$
			namespaceFile = File.createTempFile(TableReader.NAMESPACE, ".new", manager.getBase()); //$NON-NLS-1$
			TableWriter.setTableFile(tableFile);
			TableWriter.setExtraDataFile(extraFile);
			TableWriter.setMainDataFile(mainFile);
			TableWriter.setNamespaceFile(namespaceFile);
		} catch (IOException e) {
			return; //Ignore the exception since we can recompute the cache
		}
		try {
			if (new TableWriter().saveCache(registryObjects, computeRegistryStamp()))
				manager.update(new String[] {TableReader.TABLE, TableReader.MAIN, TableReader.EXTRA, TableReader.NAMESPACE}, new String[] {tableFile.getName(), mainFile.getName(), extraFile.getName(), namespaceFile.getName()});
		} catch (IOException e) {
			//Ignore the exception since we can recompute the cache
		}
	}

	private long computeRegistryStamp() {
		// If the check config prop is false or not set then exit
		if (!"true".equalsIgnoreCase(System.getProperty(InternalPlatform.PROP_CHECK_CONFIG))) //$NON-NLS-1$  
			return 0;
		Bundle[] allBundles = InternalPlatform.getDefault().getBundleContext().getBundles();
		long result = 0;
		for (int i = 0; i < allBundles.length; i++) {
			URL pluginManifest = allBundles[i].getEntry("plugin.xml"); //$NON-NLS-1$
			if (pluginManifest == null)
				pluginManifest = allBundles[i].getEntry("fragment.xml"); //$NON-NLS-1$
			if (pluginManifest == null)
				continue;
			try {
				URLConnection connection = pluginManifest.openConnection();
				result ^= connection.getLastModified() + allBundles[i].getBundleId();
			} catch (IOException e) {
				return 0;
			}
		}
		return result;
	}
}