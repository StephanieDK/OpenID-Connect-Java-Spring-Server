/*******************************************************************************
 * Copyright 2015 The MITRE Corporation
 *   and the MIT Kerberos and Internet Trust Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.mitre.openid.connect.web;

import static org.mitre.util.JsonUtils.getAsLong;
import static org.mitre.util.JsonUtils.getAsString;
import static org.mitre.util.JsonUtils.getAsStringSet;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.mitre.oauth2.service.SystemScopeService;
import org.mitre.openid.connect.config.ConfigurationPropertiesBean;
import org.mitre.openid.connect.model.ResourceSet;
import org.mitre.openid.connect.service.ResourceSetService;
import org.mitre.openid.connect.view.HttpCodeView;
import org.mitre.openid.connect.view.JsonEntityView;
import org.mitre.openid.connect.view.JsonErrorView;
import org.mitre.openid.connect.view.ResourceSetEntityAbbreviatedView;
import org.mitre.openid.connect.view.ResourceSetEntityView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.exceptions.InsufficientScopeException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

@Controller
@RequestMapping("/" + ResourceSetRegistrationEndpoint.URL)
@PreAuthorize("hasRole('ROLE_USER')")
public class ResourceSetRegistrationEndpoint {

	private static final Logger logger = LoggerFactory.getLogger(ResourceSetRegistrationEndpoint.class);
	
	public static final String URL = "resource_set/resource_set";

	@Autowired
	private ResourceSetService resourceSetService;
	
	@Autowired
	private ConfigurationPropertiesBean config;
	
	private JsonParser parser = new JsonParser();
	
	@RequestMapping(method = RequestMethod.POST, produces = MimeTypeUtils.APPLICATION_JSON_VALUE, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
	public String createResourceSet(@RequestBody String jsonString, Model m, Authentication auth) {
		ensureOAuthScope(auth);
		
		ResourceSet rs = parseResourceSet(jsonString);
		
		if (rs == null // there was no resource set in the body
				|| Strings.isNullOrEmpty(rs.getName()) // there was no name (required)
				|| rs.getScopes() == null // there were no scopes (required)
			) {

			logger.warn("Resource set registration missing one or more required fields.");
			
			m.addAttribute("code", HttpStatus.BAD_REQUEST);
			m.addAttribute("error_description", "Resource request was missing one or more required fields.");
			return JsonErrorView.VIEWNAME;
		}
		
		rs.setOwner(auth.getName());
		
		ResourceSet saved = resourceSetService.saveNew(rs);
		
		m.addAttribute("code", HttpStatus.CREATED);
		m.addAttribute("entity", saved);
		m.addAttribute("location", config.getIssuer() + URL + "/" + rs.getId());
		
		return ResourceSetEntityAbbreviatedView.VIEWNAME;
		
	}

	@RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
	public String readResourceSet(@PathVariable ("id") Long id, Model m, Authentication auth) {
		ensureOAuthScope(auth);
		
		ResourceSet rs = resourceSetService.getById(id);
		
		if (rs == null) {
			m.addAttribute("code", HttpStatus.NOT_FOUND);
			m.addAttribute("error", "not_found");
			return JsonErrorView.VIEWNAME;
		} else {
			
			if (!auth.getName().equals(rs.getOwner())) {
				
				logger.warn("Unauthorized resource set request from bad user; expected " + rs.getOwner() + " got " + auth.getName());
				
				// it wasn't issued to this user
				m.addAttribute("code", HttpStatus.FORBIDDEN);
				return JsonErrorView.VIEWNAME;
			} else {
				m.addAttribute("entity", rs);
				return ResourceSetEntityView.VIEWNAME;
			}
			
		}
		
	}

	@RequestMapping(value = "/{id}", method = RequestMethod.PUT, consumes = MimeTypeUtils.APPLICATION_JSON_VALUE, produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
	public String updateResourceSet(@PathVariable ("id") Long id, @RequestBody String jsonString, Model m, Authentication auth) {
		ensureOAuthScope(auth);

		ResourceSet newRs = parseResourceSet(jsonString);

		if (newRs == null // there was no resource set in the body
				|| Strings.isNullOrEmpty(newRs.getName()) // there was no name (required)
				|| newRs.getScopes() == null // there were no scopes (required)
				|| newRs.getId() == null || !newRs.getId().equals(id) // the IDs didn't match
			) {

			logger.warn("Resource set registration missing one or more required fields.");
			
			m.addAttribute("code", HttpStatus.BAD_REQUEST);
			m.addAttribute("error_description", "Resource request was missing one or more required fields.");
			return JsonErrorView.VIEWNAME;
		}

		ResourceSet rs = resourceSetService.getById(id);
		
		if (rs == null) {
			m.addAttribute("code", HttpStatus.NOT_FOUND);
			m.addAttribute("error", "not_found");
			return JsonErrorView.VIEWNAME;
		} else {
			if (!auth.getName().equals(rs.getOwner())) {
				
				logger.warn("Unauthorized resource set request from bad user; expected " + rs.getOwner() + " got " + auth.getName());
				
				// it wasn't issued to this user
				m.addAttribute("code", HttpStatus.FORBIDDEN);
				return JsonErrorView.VIEWNAME;
			} else {
				
				ResourceSet saved = resourceSetService.update(rs, newRs);
				
				m.addAttribute("entity", saved);
				m.addAttribute("location", config.getIssuer() + URL + "/" + rs.getId());
				return ResourceSetEntityAbbreviatedView.VIEWNAME;
			}
			
		}
	}
	
	@RequestMapping(value = "/{id}", method = RequestMethod.DELETE, produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
	public String deleteResourceSet(@PathVariable ("id") Long id, Model m, Authentication auth) {
		ensureOAuthScope(auth);

		ResourceSet rs = resourceSetService.getById(id);
		
		if (rs == null) {
			m.addAttribute("code", HttpStatus.NOT_FOUND);
			m.addAttribute("error", "not_found");
			return JsonErrorView.VIEWNAME;
		} else {
			if (!auth.getName().equals(rs.getOwner())) {
				
				logger.warn("Unauthorized resource set request from bad user; expected " + rs.getOwner() + " got " + auth.getName());
				
				// it wasn't issued to this user
				m.addAttribute("code", HttpStatus.FORBIDDEN);
				return JsonErrorView.VIEWNAME;
			} else {
				
				resourceSetService.remove(rs);
				
				m.addAttribute("code", HttpStatus.NO_CONTENT);
				return HttpCodeView.VIEWNAME;
			}
			
		}
	}
	
	@RequestMapping(method = RequestMethod.GET, produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
	public String listResourceSets(Model m, Authentication auth) {
		ensureOAuthScope(auth);
		
		String owner = auth.getName();
		
		Collection<ResourceSet> resourceSets = resourceSetService.getAllForOwner(owner);
		
		// build the entity here and send to the display
		
		Set<String> ids = new HashSet<>();
		for (ResourceSet resourceSet : resourceSets) {
			ids.add(resourceSet.getId().toString()); // add them all as strings so that gson renders them properly
		}
		
		m.addAttribute("entity", ids);
		return JsonEntityView.VIEWNAME;
	}

	private void ensureOAuthScope(Authentication auth) {
		// if auth is OAuth, make sure we've got the right scope
		if (auth instanceof OAuth2Authentication) {
			OAuth2Authentication oAuth2Authentication = (OAuth2Authentication) auth;
			if (oAuth2Authentication.getOAuth2Request().getScope() == null
					|| !oAuth2Authentication.getOAuth2Request().getScope().contains(SystemScopeService.RESOURCE_SET_REGISTRATION_SCOPE)) {
				throw new InsufficientScopeException("Insufficient scope", ImmutableSet.of(SystemScopeService.RESOURCE_SET_REGISTRATION_SCOPE));
			}
		}
	}
	
	private ResourceSet parseResourceSet(String jsonString) {

		try {
			JsonElement el = parser.parse(jsonString);
			
			if (el.isJsonObject()) {
				JsonObject o = el.getAsJsonObject();
				
				ResourceSet rs = new ResourceSet();
				rs.setId(getAsLong(o, "_id"));
				rs.setName(getAsString(o, "name"));
				rs.setIconUri(getAsString(o, "icon_uri"));
				rs.setType(getAsString(o, "type"));
				rs.setScopes(getAsStringSet(o, "scopes"));
				rs.setUri(getAsString(o, "uri"));
				
				return rs;
				
			}
			
			return null;
			
		} catch (JsonParseException e) {
			return null;
		}
		
	}
	
}