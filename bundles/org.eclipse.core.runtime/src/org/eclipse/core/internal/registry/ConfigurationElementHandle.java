package org.eclipse.core.internal.registry;

import org.eclipse.core.runtime.*;

public class ConfigurationElementHandle extends Handle implements IConfigurationElement {
	static final ConfigurationElementHandle[] EMPTY_ARRAY = new ConfigurationElementHandle[0];

	public ConfigurationElementHandle(int id) {
		super(id);
	}

	protected ConfigurationElement getConfigurationElement() {
		return (ConfigurationElement) objectManager.getObject(getId(), RegistryObjectManager.CONFIGURATION_ELEMENT);
	}

	public String getAttribute(String propertyName) {
		return getConfigurationElement().getAttribute(propertyName);
	}

	public String[] getAttributeNames() {
		return getConfigurationElement().getAttributeNames();
	}

	public IConfigurationElement[] getChildren() {
		ConfigurationElement actualCe = getConfigurationElement();
		if (actualCe.extraDataOffset == -1) {
			return (IConfigurationElement[]) objectManager.getHandles(actualCe.getRawChildren(), RegistryObjectManager.CONFIGURATION_ELEMENT);
		}
		return (IConfigurationElement[]) objectManager.getHandles(actualCe.getRawChildren(), RegistryObjectManager.THIRDLEVEL_CONFIGURATION_ELEMENT);
	}

	public Object createExecutableExtension(String propertyName) throws CoreException {
		return getConfigurationElement().createExecutableExtension(propertyName);
	}

	public String getAttributeAsIs(String name) {
		return getConfigurationElement().getAttributeAsIs(name);
	}

	public IConfigurationElement[] getChildren(String name) {
		ConfigurationElement actualCE = getConfigurationElement();
		ConfigurationElement[] children = (ConfigurationElement[]) objectManager.getObjects(actualCE.getRawChildren(), actualCE.extraDataOffset == -1 ? RegistryObjectManager.CONFIGURATION_ELEMENT : RegistryObjectManager.THIRDLEVEL_CONFIGURATION_ELEMENT);
		if (children.length == 0)
			return ConfigurationElementHandle.EMPTY_ARRAY;

		IConfigurationElement[] result = new IConfigurationElement[1];
		int idx = 0;
		for (int i = 0; i < children.length; i++) {
			if (children[i].getName().equals(name)) {
				if (idx != 0) {
					IConfigurationElement[] copy = new IConfigurationElement[result.length + 1];
					System.arraycopy(result, 0, copy, 0, result.length);
					result = copy;
				}
				result[idx++] = (IConfigurationElement) objectManager.getHandle(children[i].getObjectId(), actualCE.extraDataOffset == -1 ? RegistryObjectManager.CONFIGURATION_ELEMENT : RegistryObjectManager.THIRDLEVEL_CONFIGURATION_ELEMENT);
			}
		}
		if (idx == 0)
			return ConfigurationElementHandle.EMPTY_ARRAY;
		return result;
	}

	public IExtension getDeclaringExtension() {
		Object result = this;
		while (! ((result = ((ConfigurationElementHandle) result).getParent()) instanceof ExtensionHandle)) { /*do nothing*/ }
		return (IExtension) result;
	}

	public String getName() {
		return getConfigurationElement().getName();
	}

	public Object getParent() {
		//Because we are not sure what is the parent, we try to get the object
		// with a type. If this fails then we try with the other type.
		ConfigurationElement actualCe = getConfigurationElement();
		return objectManager.getHandle(actualCe.parentId, actualCe.parentType);
	}

	public String getValue() {
		return getConfigurationElement().getValue();
	}

	public String getValueAsIs() {
		return getConfigurationElement().getValueAsIs();
	}

	NestedRegistryModelObject getObject() {
		return getConfigurationElement();
	}

	public String getNamespace() {
		return getDeclaringExtension().getNamespace();
	}
}
