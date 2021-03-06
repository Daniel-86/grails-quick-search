package org.grails.plugins.quickSearch

import org.apache.commons.lang.ClassUtils
import org.apache.commons.logging.LogFactory
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty

import java.text.Normalizer

/**
 * Util class for searching.
 *
 * @author <a href="mailto:matous.kucera@tado.com">Matouš Kučera</a>
 * @since 14.11.13 11:42
 */
class QuickSearchUtil {

   private static final log = LogFactory.getLog(this)
   private static final TOKENS = ' '
   private static final TOKENIZE_NUMBERS = true
   private static final TOKENIZE_WRAPPER = '"'

   static getDomainClassProperties(def grailsApplication, def domainClass, def strings = true, def numbers = true) {
      def properties = grailsApplication.getDomainClass(domainClass.name).persistentProperties

      if (grailsApplication.config.grails.plugins.quickSearch.search.searchIdentifier)
         properties += grailsApplication.getDomainClass(domainClass.name).identifier

      properties.findAll {
         def useProperty = false
         if (strings) {
            useProperty = (it.getType() == String)
         }
         if (!useProperty && numbers) {
            useProperty = (ClassUtils.isAssignable(it.type, Number.class, true))
         }
         return useProperty
      }.collect{it.name}
   }

   static getPropertyByDotName(Object object, String property) {
      property.tokenize('.').inject object, {obj, prop ->
         obj ? obj[prop] : null
      }
   }

   static getPropertyType(def grailsApplication, def domainClass, def property) {
      def properties = property.split("\\.")
      def firstPropertyName = (properties.size() > 0) ? properties[0] : null
      def grailsDomainClass = (domainClass instanceof GrailsDomainClass) ? domainClass : grailsApplication.getDomainClass(domainClass.name)
      def firstProperty = grailsDomainClass?.getPropertyByName(firstPropertyName)

      if (firstProperty?.isAssociation()) {
         def startIndex = property.indexOf(".")
         if (startIndex >= 0) {
            def referencedDomainClass = firstProperty.isEmbedded() ? firstProperty.component : firstProperty.referencedDomainClass
            // recursion
            getPropertyType(grailsApplication, referencedDomainClass, property.substring(startIndex+1, property.length()))
         }
         else {
            log.error "Cannot resolve property of domain class $domainClass by its name $property"
            return null
         }
      }
      else {
         firstProperty.getType()
      }
   }

   static splitQuery(def grailsApplication, def query, def tokens, def tokenizeNumbers, def tokenWrapper) {
      def resultQueries = []
      // token wrapper
      def _tokenWrapper = (tokenWrapper != null) ? tokenWrapper :
         (grailsApplication.config.grails.plugins.quickSearch.search.tokenWrapper != null ?
            grailsApplication.config.grails.plugins.quickSearch.search.tokenWrapper :
            TOKENIZE_WRAPPER)
      if (!_tokenWrapper.isEmpty() && query?.startsWith(_tokenWrapper) && query?.endsWith(_tokenWrapper)
         && query?.size() > 2) {
         resultQueries.add(query.substring(1, query.size() -1 ))
      } else {
         // use tokenizer
         def _tokens = (tokens != null) ? tokens : (grailsApplication.config.grails.plugins.quickSearch.search.tokens ?: TOKENS)
         def queries = (_tokens?.size() > 0) ? query?.tokenize(_tokens) : [query]
         // tokenize numbers
         def _tokenizeNumbers = (tokenizeNumbers != null) ?
            tokenizeNumbers :
            ((grailsApplication.config.grails.plugins.quickSearch.search.tokenizeNumbers == true || grailsApplication.config.grails.plugins.quickSearch.search.tokenizeNumbers == false) ?
               grailsApplication.config.grails.plugins.quickSearch.search.tokenizeNumbers : TOKENIZE_NUMBERS)

         if (_tokenizeNumbers) {
            queries.each {
               resultQueries.addAll(it.findAll(/\d+/)) // add numbers
               resultQueries.addAll(it.findAll(/[^\d]+/)) // add strings
            }
         } else {
            resultQueries = queries
         }
      }


      return resultQueries
   }

   static findMatchResults(Object object, def searchProperties, def queries) {
      searchProperties.collectEntries { key, dotName ->
         [(key): getPropertyByDotName(object, dotName)]
      }.findAll { key, property ->
         queries.find { query -> matchSearch(property, query)} != null
      }
   }

   static matchSearch(def property, def query) {
      if (property) {
         if (property instanceof String) {
            def propertyNormalized = Normalizer.normalize(property, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "").toLowerCase();
            def queryNormalized = Normalizer.normalize(query, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "").toLowerCase();
            return propertyNormalized.contains(queryNormalized)
         } else if (ClassUtils.isAssignable(property.class, Number.class, true)) {
            if (query.isNumber())
               try {
                  return property == query.asType(property.class)
               }
               catch (NumberFormatException e) {
                  log.warn "Queried string [$query] could not be translated to number."
               }
         } else {
            log.error "Unsupported class type [${property.class}] for quick search, omitting."
         }
      } else {
         return false
      }
   }
}
