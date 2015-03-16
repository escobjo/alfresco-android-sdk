/*******************************************************************************
 * Copyright (C) 2005-2012 Alfresco Software Limited.
 * 
 * This file is part of the Alfresco Mobile SDK.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 ******************************************************************************/
package org.alfresco.mobile.android.api.services.impl;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.alfresco.cmis.client.AlfrescoDocument;
import org.alfresco.mobile.android.api.model.ContentFile;
import org.alfresco.mobile.android.api.model.Document;
import org.alfresco.mobile.android.api.model.ListingContext;
import org.alfresco.mobile.android.api.model.Node;
import org.alfresco.mobile.android.api.model.PagingResult;
import org.alfresco.mobile.android.api.model.impl.DocumentImpl;
import org.alfresco.mobile.android.api.model.impl.PagingResultImpl;
import org.alfresco.mobile.android.api.services.ServiceRegistry;
import org.alfresco.mobile.android.api.services.VersionService;
import org.alfresco.mobile.android.api.session.AlfrescoSession;
import org.alfresco.mobile.android.api.session.impl.AbstractAlfrescoSessionImpl;
import org.alfresco.mobile.android.api.utils.IOUtils;
import org.alfresco.mobile.android.api.utils.NodeComparator;
import org.alfresco.mobile.android.api.utils.messages.Messagesl18n;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Implementation of VersionService.
 * 
 * @author Jean Marie Pascal
 */
public class VersionServiceImpl extends AlfrescoService implements VersionService
{
    protected Session cmisSession;

    /**
     * Default constructor for service. </br> Used by the
     * {@link ServiceRegistry}.
     * 
     * @param repositorySession
     */
    public VersionServiceImpl(AlfrescoSession repositorySession)
    {
        super(repositorySession);
        this.cmisSession = ((AbstractAlfrescoSessionImpl) repositorySession).getCmisSession();
    }

    public Document getLatestVersion(Document document)
    {
        if (isObjectNull(document)) { throw new IllegalArgumentException(String.format(
                Messagesl18n.getString("ErrorCodeRegistry.GENERAL_INVALID_ARG_NULL"), "document")); }

        try
        {
            VersioningService versioningService = cmisSession.getBinding().getVersioningService();
            OperationContext ctxt = cmisSession.getDefaultContext();
            ObjectFactory objectFactory = cmisSession.getObjectFactory();

            ObjectData objectData = versioningService.getObjectOfLatestVersion(session.getRepositoryInfo()
                    .getIdentifier(), document.getIdentifier(),
                    (String) document.getProperty(PropertyIds.VERSION_SERIES_ID).getValue(), false, ctxt
                            .getFilterString(), ctxt.isIncludeAllowableActions(), ctxt.getIncludeRelationships(), ctxt
                            .getRenditionFilterString(), ctxt.isIncludePolicies(), ctxt.isIncludeAcls(), null);

            return (Document) convertNode(objectFactory.convertObject(objectData, ctxt));
        }
        catch (Exception e)
        {
            convertException(e);
        }
        return null;
    }

    /** {@inheritDoc} */
    public List<Document> getVersions(Document document)
    {
        return getVersions(document, null).getList();
    }

    /** {@inheritDoc} */
    public PagingResult<Document> getVersions(Document document, ListingContext listingContext)
    {
        return computeVersion(document, listingContext);
    }

    // ////////////////////////////////////////////////////////////////////////////////////
    // CHECKIN / CHECKOUT
    // ////////////////////////////////////////////////////////////////////////////////////
    /** {@inheritDoc} */
    @Override
    public Document checkout(Document document)
    {
        if (isObjectNull(document)) { throw new IllegalArgumentException(String.format(
                Messagesl18n.getString("ErrorCodeRegistry.GENERAL_INVALID_ARG_NULL"), "document")); }

        try
        {
            AlfrescoDocument cmisDoc = (AlfrescoDocument) cmisSession.getObject(document.getIdentifier());
            String idpwc = cmisDoc.checkOut().getId();

            return (Document) session.getServiceRegistry().getDocumentFolderService().getNodeByIdentifier(idpwc);
        }
        catch (Exception e)
        {
            convertException(e);
        }

        return null;
    }

    @Override
    /** {@inheritDoc} */
    public void cancelCheckout(Document document)
    {
        if (isObjectNull(document)) { throw new IllegalArgumentException(String.format(
                Messagesl18n.getString("ErrorCodeRegistry.GENERAL_INVALID_ARG_NULL"), "document")); }

        try
        {
            VersioningService versioningService = cmisSession.getBinding().getVersioningService();
            AlfrescoDocument cmisDoc = (AlfrescoDocument) cmisSession.getObject(document.getIdentifier());
            String idpwc = cmisDoc.getVersionSeriesCheckedOutId();

            if (idpwc != null)
            {
                versioningService.cancelCheckOut(session.getRepositoryInfo().getIdentifier(), idpwc, null);
            }
        }
        catch (Exception e)
        {
            convertException(e);
        }
    }

    @Override
    /** {@inheritDoc} */
    public Document checkin(Document document, boolean majorVersion, ContentFile contentFile,
            Map<String, Serializable> properties, String comment)
    {
        if (isObjectNull(document)) { throw new IllegalArgumentException(String.format(
                Messagesl18n.getString("ErrorCodeRegistry.GENERAL_INVALID_ARG_NULL"), "document")); }

        try
        {
            AlfrescoDocument cmisDoc = (AlfrescoDocument) cmisSession.getObject(document.getIdentifier());
            String idpwc = cmisDoc.getVersionSeriesCheckedOutId();
            org.apache.chemistry.opencmis.client.api.Document cmisDocpwc = null;

            if (idpwc != null)
            {
                cmisDocpwc = (org.apache.chemistry.opencmis.client.api.Document) cmisSession.getObject(idpwc);
                
                ContentStream c = cmisSession.getObjectFactory().createContentStream(contentFile.getFileName(),
                        contentFile.getLength(), contentFile.getMimeType(),
                        IOUtils.getContentFileInputStream(contentFile));
                
                ObjectId iddoc = cmisDocpwc.checkIn(majorVersion, properties, c, comment);
                
                return (Document) session.getServiceRegistry().getDocumentFolderService().getNodeByIdentifier(iddoc.getId());
            }
        }
        catch (Exception e)
        {
            convertException(e);
        }
        return null;
    }

    @Override
    /** {@inheritDoc} */
    public List<Document> getCheckedOutDocuments()
    {
        return getCheckedOutDocuments(null).getList();
    }

    @Override
    /** {@inheritDoc} */
    public PagingResult<Document> getCheckedOutDocuments(ListingContext lcontext)
    {
        try
        {
            OperationContext ctxt = new OperationContextImpl(cmisSession.getDefaultContext());

            // By default Listing context has default value
            String orderBy = AbstractDocumentFolderServiceImpl.getSorting(SORT_PROPERTY_NAME, true);
            BigInteger maxItems = null;
            BigInteger skipCount = null;

            if (lcontext != null)
            {
                orderBy = AbstractDocumentFolderServiceImpl.getSorting(lcontext.getSortProperty(),
                        lcontext.isSortAscending());
                maxItems = BigInteger.valueOf(lcontext.getMaxItems());
                if (maxItems != null)
                {
                    ctxt.setMaxItemsPerPage(maxItems.intValue());
                }
                skipCount = BigInteger.valueOf(lcontext.getSkipCount());
            }

            // Order By
            ctxt.setOrderBy(orderBy);

            // get the checkOuts documents
            ItemIterable<org.apache.chemistry.opencmis.client.api.Document> checkedOutDocs = cmisSession
                    .getCheckedOutDocs(ctxt);

            if (skipCount != null)
            {
                checkedOutDocs = checkedOutDocs.skipTo(skipCount.longValue());
            }
            
            // convert objects
            List<Document> page = new ArrayList<Document>();
            for (org.apache.chemistry.opencmis.client.api.Document cmisDoc : checkedOutDocs.getPage())
            {
                if (cmisDoc != null)
                {
                    page.add(new DocumentImpl(cmisDoc));
                }
            }

            Boolean hasMoreItem = false;
            if (maxItems != null)
            {
                hasMoreItem = checkedOutDocs.getHasMoreItems() && page.size() == maxItems.intValue();
            }
            else
            {
                hasMoreItem = checkedOutDocs.getHasMoreItems();
            }

            return new PagingResultImpl<Document>(page, hasMoreItem, (int) checkedOutDocs.getTotalNumItems());
        }
        catch (Exception e)
        {
            convertException(e);
        }
        return null;
    }

    // ////////////////////////////////////////////////////////////////////////////////////
    // / INTERNAL
    // ////////////////////////////////////////////////////////////////////////////////////
    /**
     * Internal method to compute data from server and transform it as high
     * level object.
     * 
     * @param document : versionned document
     * @param listingContext : define characteristics of the result
     * @return Paging Result of document that represent one version of a
     *         document.
     */
    private PagingResult<Document> computeVersion(Document document, ListingContext listingContext)
    {
        if (isObjectNull(document)) { throw new IllegalArgumentException(String.format(
                Messagesl18n.getString("ErrorCodeRegistry.GENERAL_INVALID_ARG_NULL"), "document")); }

        try
        {
            VersioningService versioningService = cmisSession.getBinding().getVersioningService();
            OperationContext ctxt = cmisSession.getDefaultContext();
            ObjectFactory objectFactory = cmisSession.getObjectFactory();

            List<ObjectData> versions = versioningService.getAllVersions(session.getRepositoryInfo().getIdentifier(),
                    document.getIdentifier(), (String) document.getProperty(PropertyIds.VERSION_SERIES_ID).getValue(),
                    ctxt.getFilterString(), ctxt.isIncludeAllowableActions(), null);

            int size = (versions != null) ? versions.size() : 0;

            Boolean hasMoreItems = false;
            // Define Listing Context
            if (listingContext != null && versions != null)
            {
                int fromIndex = (listingContext.getSkipCount() > size) ? size : listingContext.getSkipCount();

                // Case if skipCount > result size
                if (listingContext.getMaxItems() + fromIndex >= size)
                {
                    versions = versions.subList(fromIndex, size);
                    hasMoreItems = false;
                }
                else
                {
                    versions = versions.subList(fromIndex, listingContext.getMaxItems() + fromIndex);
                    hasMoreItems = true;
                }
            }

            // Create list
            List<Document> result = new ArrayList<Document>();
            if (versions != null)
            {
                for (ObjectData objectData : versions)
                {
                    Node doc = convertNode(objectFactory.convertObject(objectData, ctxt));
                    if (!(doc instanceof Document))
                    {
                        // should not happen...
                        continue;
                    }
                    result.add((Document) doc);
                }
            }

            if (listingContext != null)
            {
                Collections.sort(result,
                        new NodeComparator(listingContext.isSortAscending(), listingContext.getSortProperty()));
            }

            return new PagingResultImpl<Document>(result, hasMoreItems, size);
        }
        catch (Exception e)
        {
            convertException(e);
        }
        return null;
    }

    // ////////////////////////////////////////////////////
    // Save State - serialization / deserialization
    // ////////////////////////////////////////////////////
    public static final Parcelable.Creator<VersionServiceImpl> CREATOR = new Parcelable.Creator<VersionServiceImpl>()
    {
        public VersionServiceImpl createFromParcel(Parcel in)
        {
            return new VersionServiceImpl(in);
        }

        public VersionServiceImpl[] newArray(int size)
        {
            return new VersionServiceImpl[size];
        }
    };

    public VersionServiceImpl(Parcel o)
    {
        super((AlfrescoSession) o.readParcelable(AlfrescoSession.class.getClassLoader()));
    }
}
