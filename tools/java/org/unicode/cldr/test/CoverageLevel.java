/*
 *******************************************************************************
 * Copyright (C) 1996-2005, International Business Machines Corporation and    *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 */
package org.unicode.cldr.test;

import org.unicode.cldr.test.CheckCLDR.CheckStatus;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.StandardCodes;
import org.unicode.cldr.util.Utility;
import org.unicode.cldr.util.XMLSource;
import org.unicode.cldr.util.XPathParts;

import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supports testing of paths in a file to see if they meet the coverage levels according to the UTS.<br>
 * Call init() exactly once with the supplementatData file and the supplementalMetadata file.<br>
 * For each new file, call setFile()<br>
 * Then for each path in the file, call getCoverageLevel(). You can then compare that to the desired level.<br>
 * There is a utility routine that will get the requiredLevel from the organization.
 * For an example, see CheckCoverage.
 * @author davis
 *
 */
public class CoverageLevel {
  public static final String EUROPEAN_UNION = "QU";
  
  /**
   * A simple class representing an enumeration of possible CLDR coverage levels. Levels may change in the future.
   * @author davis
   *
   */
  public static class Level implements Comparable {
    private static List all = new ArrayList();
    private byte level;
    private String name;
    private String altName;
    
    private Level(int i, String name, String altName) {
      level = (byte) i;
      this.name = name;
      this.altName = altName;
      all.add(this);
    }
    
    public static final Level 
    UNDETERMINED = new Level(0, "none", "none"),
    POSIX = new Level(20,"posix", "G4"),
    MINIMAL = new Level(30,"minimal", "G3.5"),
    BASIC = new Level(40,"basic", "G3"),
    MODERATE = new Level(60, "moderate", "G2"),
    MODERN = new Level(80, "modern", "G1"),
    COMPREHENSIVE = new Level(100, "comprehensive", "G0");
    
    public static Level get(String name) {
      for (int i = 0; i < all.size(); ++i) {
        Level item = (Level) all.get(i);
        if (item.name.equalsIgnoreCase(name)) return item;
        if (item.altName.equalsIgnoreCase(name)) return item;
      }
      return UNDETERMINED;
    }
    
    public String toString() {
      return name;
    }
    
    public int compareTo(Object o) {
      int otherLevel = ((Level) o).level;
      return level < otherLevel ? -1 : level > otherLevel ? 1 : 0;
    }
  }
  
  private static Object sync = new Object();
  
  // commmon stuff, set once
  private static Map coverageData = new TreeMap();
  private static Map base_language_level = new TreeMap();
  private static Map base_script_level = new TreeMap();
  private static Map base_territory_level = new TreeMap();
  private static Set minimalTimezones;
  private static Set euroCountries;
  private static Set territoryContainment = new TreeSet();
  private static Set euroLanguages = new TreeSet();
  
  private static Map language_scripts = new TreeMap();
  
  private static Map language_territories = new TreeMap();
  private static Map territory_languages = new TreeMap();
  
  private static Set modernLanguages = new TreeSet();
  private static Set modernScripts = new TreeSet();
  private static Set modernTerritories = new TreeSet();
  //private static Map locale_requiredLevel = new TreeMap();
  private static Map territory_currency = new TreeMap();
  private static Map territory_timezone = new TreeMap();
  private static Map territory_calendar = new TreeMap();
  private static Set modernCurrencies = new TreeSet();
  
  private static Map<String,Level> posixCoverage = new TreeMap();
  // current stuff, set according to file
  
  private boolean initialized = false;
  
  private transient LocaleIDParser parser = new LocaleIDParser();
  
  private transient XPathParts parts = new XPathParts(null, null);
  
  private Map<String, CoverageLevel.Level> language_level = new TreeMap();
  
  private Map script_level = new TreeMap();
  private Map zone_level = new TreeMap();
  private Map<String, CoverageLevel.Level> metazone_level = new TreeMap();
  
  private Map territory_level = new TreeMap();
  private Map currency_level = new TreeMap();
  private Map calendar_level = new TreeMap();
  
  StandardCodes sc = StandardCodes.make();
  
  boolean exemplarsContainA_Z = false;

  private boolean currencyExemplarsContainA_Z;
  
  private static boolean euroCountriesMissing = false; // Set to TRUE if eurocountries weren't produced by init.
  
  /**
   * Used by the coverage & survey tools.
   * @param file only used to get at the supplemental data, since from any CLDRFile you can get to siblings
   * @param options optional parameters
   * @param cause TODO
   * @param possibleErrors if there are errors or warnings, those are added (as CheckStatus objects) to this list.
   */
  public void setFile(CLDRFile file, Map options, CheckCLDR cause, List possibleErrors) {
    synchronized (sync) {
      if (!initialized) {
        CLDRFile supplementalMetadata = file.getSupplementalMetadata();
        CLDRFile supplementalData = file.getSupplementalData();
        init(supplementalData, supplementalMetadata);
        initMetazoneCoverage(supplementalData);
        initPosixCoverage(file.getLocaleID(), supplementalData);
        initialized = true;
      }
    }
    boolean exemplarsContainA_Z = false;
    UnicodeSet exemplars = file.getResolved().getExemplarSet("", CLDRFile.WinningChoice.WINNING); // need to use resolved version to get exemplars
    
    if(exemplars == null) {
        throw new InternalError("'"+file.getLocaleID()+"'.getExemplarSet() returned null.");
    }
    
    UnicodeSet auxexemplars = file.getExemplarSet("auxiliary", CLDRFile.WinningChoice.WINNING);
    if (auxexemplars != null) exemplars.addAll(auxexemplars);
    exemplarsContainA_Z = exemplars.contains('A','Z');
    
    boolean currencyExemplarsContainA_Z = false;
    auxexemplars = file.getExemplarSet("currencySymbol", CLDRFile.WinningChoice.WINNING);
    if (auxexemplars != null) currencyExemplarsContainA_Z = auxexemplars.contains('A','Z');

    setFile(file.getLocaleID(), exemplarsContainA_Z, currencyExemplarsContainA_Z, options, cause, possibleErrors);
  }
  
  /**
   * Utility for getting the *default* required coverage level for a locale.
   * @param localeID
   * @param options
   * @return
   */
  public Level getRequiredLevel(String localeID, Map<String,String> options) {
    Level result;
    // see if there is an explicit level
    String localeType = options.get("CoverageLevel.requiredLevel");
    if (localeType != null) {
      result = Level.get(localeType);
      if (result != Level.UNDETERMINED) {
        return result;
      }
    }
    // otherwise, see if there is an organization level
    return sc.getLocaleCoverageLevel(options.get("CoverageLevel.localeType"), localeID);
  }
  
  /**
   * Separate interface for the configuration tool. Note: init() must be called exactly once
   * before calling this.
   * @param localeID the localeID for the file
   * @param exemplarsContainA_Z true if the union of the exemplar sets contains A-Z
   * @param currencyExemplarsContainA_Z TODO
   * @param options optional parameters
   * @param cause TODO
   * @param possibleErrors if there are errors or warnings, those are added (as CheckStatus objects) to this list.
   */
  public void setFile(String localeID, boolean exemplarsContainA_Z, boolean currencyExemplarsContainA_Z, Map options, CheckCLDR cause, List possibleErrors) {
    this.exemplarsContainA_Z = exemplarsContainA_Z;
    this.currencyExemplarsContainA_Z = currencyExemplarsContainA_Z;
    
    parser.set(localeID);
    String language = parser.getLanguage();
    
    // do the work of putting together the coverage info
    language_level.clear();
    script_level.clear();
    currency_level.clear();
    zone_level.clear();
    calendar_level.clear();
    
    
    language_level.putAll(base_language_level);
    
    script_level.putAll(base_script_level);
    try {
      Set scriptsForLanguage = (Set) language_scripts.get(language);
      if (scriptsForLanguage != null && scriptsForLanguage.size() > 1) {
        putAll(script_level, scriptsForLanguage, CoverageLevel.Level.MINIMAL, true);
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
    
    territory_level.putAll(base_territory_level);
    putAll(territory_level, (Set) language_territories.get(language), CoverageLevel.Level.MINIMAL, true);
    
    {
      language_level.put(language, CoverageLevel.Level.MINIMAL);
      String script = parser.getScript();
      if (script != null) {
        script_level.put(script, CoverageLevel.Level.MINIMAL);
      }
      String territory = parser.getRegion();
      if (territory != null) {
        territory_level.put(territory, CoverageLevel.Level.MINIMAL);
      }
    }
    
    // special cases for EU
    if (euroLanguages.contains(language)) {
      setIfBetter(language_level, euroLanguages, CoverageLevel.Level.MODERATE, true);
      setIfBetter(territory_level, euroCountries, CoverageLevel.Level.MODERATE, true);
    }
    // special case pt_BR, zh_Hant, zh_Hans
    Level x = language_level.get("zh");
    if (x != null) {
      language_level.put("zh_Hans", x);
      language_level.put("zh_Hant", x);
    }
    x = language_level.get("pt");
    if (x != null) {
      language_level.put("pt_BR", x);
    }
    
    setIfBetter(territory_level, territoryContainment, CoverageLevel.Level.MODERATE, true);
    
    // set currencies, timezones according to territory level
    // HOWEVER, make the currency level at most BASIC
    putAll(currency_level, modernCurrencies, CoverageLevel.Level.MODERN, true);
    for (Iterator it = territory_level.keySet().iterator(); it.hasNext();) {
      String territory = (String) it.next();
      CoverageLevel.Level level = (CoverageLevel.Level) territory_level.get(territory);
      if (level.compareTo(CoverageLevel.Level.BASIC) < 0) level = CoverageLevel.Level.BASIC;
      Set currencies = (Set) territory_currency.get(territory);
      setIfBetter(currency_level, currencies, level, false);
      Set timezones = (Set) territory_timezone.get(territory);
      if (timezones != null) {
        // only worry about the ones that are "moderate"
        timezones.retainAll(minimalTimezones);
        setIfBetter(zone_level, timezones, level, false);
      }
    }
    
    // set the calendars only by the direct territories for the language
    calendar_level.put("gregorian", CoverageLevel.Level.BASIC);
    Set territories = ((Set)language_territories.get(language));
    if (territories == null) {
      possibleErrors.add(new CheckStatus()
          .setCause(cause).setType(CheckCLDR.finalErrorType)
          .setMessage("Missing language->territory information in supplemental data!"));
    } else for (Iterator it = territories.iterator(); it.hasNext();) {
      String territory = (String) it.next();
      setIfBetter(calendar_level, (Collection) territory_calendar.get(territory), CoverageLevel.Level.BASIC, true);
    }
    
    // special case XXX, Etc/Unknown
    Set temp = new HashSet();
    temp.add("XXX");
    setIfBetter(currency_level, temp, CoverageLevel.Level.BASIC, false);
    
    Set temp2 = new HashSet();
    temp2.add("Etc/Unknown");
    setIfBetter(zone_level, temp, CoverageLevel.Level.BASIC, false);
    
    
    if (CheckCoverage.DEBUG) {
      System.out.println("language_level: " + language_level);               
      System.out.println("script_level: " + script_level);
      System.out.println("territory_level: " + territory_level);
      System.out.println("currency_level: " + currency_level);
      System.out.println("euroCountries: " + euroCountries);
      System.out.println("euroLanguages: " + euroLanguages);
      System.out.flush();
    }
    
    // A complaint.
    if(euroCountriesMissing) {
      possibleErrors.add(new CheckStatus()
          .setCause(cause).setType(CheckStatus.errorType)
          .setMessage("Missing euro country information- '" + EUROPEAN_UNION + "' missing in territory codes?"));
    }
  }
  
  /**
   * For each items in keyset, targetMap.put(item, value);
   * @param targetMap
   * @param keyset
   * @param value
   * @param override TODO
   */
  private static void putAll(Map targetMap, Collection keyset, Object value, boolean override) {
    if (keyset == null) return;
    for (Iterator it2 = keyset.iterator(); it2.hasNext();) {
      Object item = it2.next();
      if (!override & targetMap.get(item) != null) {
        continue;
      }
      targetMap.put(item, value);
    }
  }
  
  private static void addAllToCollectionValue(Map targetMap, Collection keyset, Object value, Class classForNew) {
    if (keyset == null) return;
    for (Iterator it2 = keyset.iterator(); it2.hasNext();) {
      addToValueSet(targetMap, it2.next(), value, classForNew);
    }
  }
  
  private static void addToValueSet(Map targetMap, Object key, Object value, Class classForNew) {
    Collection valueSet = (Collection) targetMap.get(key);
    if (valueSet == null) try {
      targetMap.put(key, valueSet = (Collection)classForNew.newInstance());
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot create collection with " + classForNew.getName());
    }
    valueSet.add(value);
  }
  
  private void setIfBetter(Map targetMap, Collection keyCollection, CoverageLevel.Level level, boolean show) {
    if (keyCollection == null) return;
    for (Iterator it2 = keyCollection.iterator(); it2.hasNext();) {
      Object script = it2.next();
      CoverageLevel.Level old = (CoverageLevel.Level) targetMap.get(script);
      if (old == null || level.compareTo(old) < 0) {
        if (CheckCoverage.DEBUG_SET && show) System.out.println("\t" + script + "\t(" + old + " \u2192 " + level + ")");
        targetMap.put(script, level);
      }
    }
  }
  
  Matcher basicPatterns = Pattern.compile(
      "/(" +
      "measurementSystemName" +
      "|characters/exemplarCharacters*(?!\\[@type=\"currencySymbol\"])" +
      "|delimiters" +
      "|codePattern" +
      "|calendar\\[\\@type\\=\"gregorian\"\\].*(" +
      "\\[@type=\"format\"].*\\[@type=\"(wide|abbreviated)\"]" +
      "|\\[@type=\"stand-alone\"].*\\[@type=\"narrow\"]" +
      "|/eraAbbr" +
      "|/pattern" +
      "|/dateFormatItem" +
      "|/fields(?!.*relative.*(2|3))" +
      ")" +
      "|numbers/symbols/(decimal/group)" +
      "|timeZoneNames/(hourFormat|gmtFormat|regionFormat)" +
      ")").matcher("");
  
  // //ldml/dates/calendars/calendar[@type="gregorian"]/fields/field[@type="day"]/relative[@type="2"]

  public CoverageLevel.Level getCoverageLevel(String fullPath) {
    return getCoverageLevel(fullPath, null);
  }
  /**
   * Returns the coverage level of the path. This is the least level at which the path is required.
   * @param fullPath (with all information). If this is null, distinguishedPath must not be
   * @param distinguishedPath the normal key for a cldr file. If this is null, fullPath must not be.
   * @return the coverage level. UNDETERMINED is returned if there is not enough information to determine the level (initially
   * we only look at the long lists of display names, currencies, timezones, etc.).
   */
  public CoverageLevel.Level getCoverageLevel(String fullPath, String distinguishedPath) {
    if (fullPath != null && fullPath.contains("/alias")) {
      return CoverageLevel.Level.UNDETERMINED; // skip
    }
    if (distinguishedPath == null) {
      distinguishedPath = CLDRFile.getDistinguishingXPath(fullPath,null,false);
    }
    
    // the minimal level is posix, so test for that.
    CoverageLevel.Level result = posixCoverage.get(distinguishedPath); // default the level to POSIX
    if (result != null) {
      return result;
    }

    // the next check we make is for certain basic patterns, based on a regex
    if (basicPatterns.reset(distinguishedPath).find()) {
      return CoverageLevel.Level.BASIC;
    }
    
    // we now do some more complicated tests that depend on the type
    parts.set(distinguishedPath);
    String lastElement = parts.getElement(-1);
    String type = parts.getAttributeValue(-1, "type");
    String part1 = parts.getElement(1);
    
    if (lastElement.equals("exemplarCity")) {
      type = parts.getAttributeValue(-2, "type"); // it's one level up
      if (exemplarsContainA_Z && !type.equals("Etc/Unknown")) {
        result = CoverageLevel.Level.UNDETERMINED;
      } else {
        result = (CoverageLevel.Level) zone_level.get(type);
      }
    } else if (part1.equals("localeDisplayNames")) {
      if (lastElement.equals("language")) {
        // <language type=\"aa\">Afar</language>"
        result = language_level.get(type);
      } else if (lastElement.equals("territory")) {
        result = (CoverageLevel.Level) territory_level.get(type);
      } else if (lastElement.equals("script")) {
        result = (CoverageLevel.Level) script_level.get(type);
      } else if (lastElement.equals("type")) {
        String key = parts.getAttributeValue(-1, "key");
        if (key.equals("calendar")) {
          result = (CoverageLevel.Level) calendar_level.get(type);
        }
      }
      // <types><type type="big5han" key="collation">Traditional Chinese (Big5)</type>
    } else if (distinguishedPath.contains("metazone")) {
       result = metazone_level.get(distinguishedPath);
    } else if (part1.equals("numbers")) {
      /*
       * <numbers> ? <currencies> ? <currency type="BRL"> <displayName draft="true">Brazilian Real</displayName>
       */
      if (currencyExemplarsContainA_Z && lastElement.equals("symbol")) {
        result = CoverageLevel.Level.UNDETERMINED;
      } else if (lastElement.equals("displayName") || lastElement.equals("symbol")) {
        String currency = parts.getAttributeValue(-2, "type");
        result = (CoverageLevel.Level) currency_level.get(currency);
      }
    } else if (part1.equals("identity")) {
      result = Level.UNDETERMINED;
    }
    if (result == null) result = CoverageLevel.Level.COMPREHENSIVE;
    return result;
  }
  
  // ========== Initialization Stuff ===================

  /**
   * Use version without options.
   * @deprecated
   */
  public void init(CLDRFile supplementalData, CLDRFile supplementalMetadata, Map options) {
    init(supplementalData, supplementalMetadata);
  }
  /**
   * Should only be called once.
   */
  public static void init(CLDRFile supplementalData, CLDRFile supplementalMetadata) {
    try {
      
      getMetadata(supplementalMetadata);
      getData(supplementalData);
      
      // put into an easier form to use
      
      Map type_languages = (Map) coverageData.get("languageCoverage");
      Utility.putAllTransposed(type_languages, base_language_level);
      Map type_scripts = (Map) coverageData.get("scriptCoverage");
      Utility.putAllTransposed(type_scripts, base_script_level);
      Map type_territories = (Map) coverageData.get("territoryCoverage");
      Utility.putAllTransposed(type_territories, base_territory_level);
      
      Map type_timezones = (Map) coverageData.get("timezoneCoverage");
      minimalTimezones = (Set) type_timezones.get(CoverageLevel.Level.MODERATE);
      
      // add the modern stuff, after doing both of the above
      
      //modernLanguages.removeAll(base_language_level.keySet());
      putAll(base_language_level, modernLanguages, CoverageLevel.Level.MODERN, false);
      //putAll(base_language_level, sc.getGoodAvailableCodes("language"), CoverageLevel.Level.COMPREHENSIVE, false);
      
      //modernScripts.removeAll(base_script_level.keySet());
      putAll(base_script_level, modernScripts, CoverageLevel.Level.MODERN, false);
      //putAll(base_script_level, sc.getGoodAvailableCodes("script"), CoverageLevel.Level.COMPREHENSIVE, false);
      
      //modernTerritories.removeAll(base_territory_level.keySet());
      putAll(base_territory_level, modernTerritories, CoverageLevel.Level.MODERN, false);
      //putAll(base_territory_level, sc.getGoodAvailableCodes("territory"), CoverageLevel.Level.COMPREHENSIVE, false);
      
      //if(euroCountries != null) {
      for (Iterator it = euroCountries.iterator(); it.hasNext();) {
        String territory = (String) it.next();
        Collection languages = (Collection)territory_languages.get(territory);
        euroLanguages.addAll(languages);
      }
      //}
      
      if (false) {
        for (Iterator it = territory_currency.keySet().iterator(); it
        .hasNext();) {
          String territory = (String) it.next();
          System.out.print(ULocale.getDisplayCountry("und_"
              + territory, ULocale.ENGLISH)
              + "\t" + territory
              + "\t\u2192\t");
          Collection languages = (Collection) territory_languages.get(territory);
          if (languages == null || languages.size() == 0) {
            System.out.print("-NONE-");
          } else for (Iterator it2 = languages.iterator(); it2.hasNext();) {
            String language = (String) it2.next();
            System.out.print(ULocale.getDisplayLanguage(
                language, ULocale.ENGLISH)
                + " (" + language + ")"
                + ";\t");
          }
          System.out.println();
        }
      }
      
      
      if (CheckCoverage.DEBUG) {
        System.out.println("base_language_level: " + base_language_level);               
        System.out.println("base_script_level: " + base_script_level);
        System.out.println("base_territory_level: " + base_territory_level);
        System.out.flush();
      }
    } catch (RuntimeException e) {
      throw e; // just for debugging
    }
  }
  
  private void initPosixCoverage(String localeID, CLDRFile supplementalData){
    parser.set(localeID);
    //String language = parser.getLanguage();
    String territory = parser.getRegion();
    //String language = parser.getLanguage();
    //String script = parser.getScript();
    //String scpt = parser.getScript();
    
    // we have to have the name for our own locale
//  posixCoverage.put("//ldml/localeDisplayNames/languages/language[@type=\""+language+"\"]", Level.POSIX);
//  if (script != null) {
//  posixCoverage.put("//ldml/localeDisplayNames/scripts/script[@type=\""+language+"\"]", Level.POSIX);
//  }
//  if (territory != null) {
//  posixCoverage.put("//ldml/localeDisplayNames/territories/territory[@type=\""+language+"\"]", Level.POSIX);
//  }
    // TODO fix version
    // this won't actually work. Values in the file are of the form:
    
    //      supplementalData[@version="1.4"]/currencyData/region[@iso3166="MG"]/currency[@from="1983-11-01"][@iso4217="MGA"]
    //Need to walk through the file and pick out a from/to values that are valid for now. May be multiple also!!
    String currencySymbol = supplementalData.getWinningValue("//supplementalData[@version=\"1.4\"]/currencyData/region[@iso3166=\""+territory+"\"]/currency");
//  if (currencySymbol == null) {
//  throw new IllegalArgumentException("Internal Error: can't find currency for region: " + territory);
//  }
    //String fractions = supplementalData.getStringValue("//supplementalData/currencyData/fractions/info[@iso4217='"+currencySymbol+"']");
    posixCoverage.put("//ldml/posix/messages/yesstr", Level.POSIX);
    posixCoverage.put("//ldml/posix/messages/nostr", Level.POSIX);
    posixCoverage.put("//ldml/characters/exemplarCharacters", Level.POSIX);
    posixCoverage.put("//ldml/numbers/currencyFormats/currencyFormatLength/currencyFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/numbers/currencies/currency[@type=\""+currencySymbol+"\"]/symbol", Level.POSIX);
    posixCoverage.put("//ldml/numbers/currencies/currency[@type=\""+currencySymbol+"\"]/decimal", Level.POSIX);
    posixCoverage.put("//ldml/numbers/currencies/currency[@type=\""+currencySymbol+"\"]/group", Level.POSIX);
    posixCoverage.put("//ldml/numbers/symbols/decimal", Level.POSIX);
    posixCoverage.put("//ldml/numbers/symbols/group", Level.POSIX);
//    posixCoverage.put("//ldml/numbers/symbols/plusSign", Level.POSIX); // the inheritance from root is almost always right, so don't require
//    posixCoverage.put("//ldml/numbers/symbols/minusSign", Level.POSIX); // the inheritance from root is almost always right, so don't require
    posixCoverage.put("//ldml/numbers/symbols/decimal", Level.POSIX);
    posixCoverage.put("//ldml/numbers/symbols/group", Level.POSIX);
    posixCoverage.put("//ldml/numbers/decimalFormats/decimalFormatLength/decimalFormat/pattern", Level.POSIX);
//    posixCoverage.put("//ldml/numbers/symbols/nativeZeroDigit", Level.POSIX); // the inheritance from root is almost always right, so don't require
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/timeFormats/timeFormatLength[@type=\"medium\"]/timeFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/dateFormats/dateFormatLength[@type=\"short\"]/dateFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/dateTimeFormats/dateTimeFormatLength/dateTimeFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/timeFormats/timeFormatLength[@type=\"long\"]/timeFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/dateFormats/dateFormatLength[@type=\"long\"]/dateFormat/pattern", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/am", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/pm", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"sun\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"mon\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"tue\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"wed\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"thu\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"fri\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"abbreviated\"]/day[@type=\"sat\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"sun\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"mon\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"tue\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"wed\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"thu\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"fri\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/days/dayContext[@type=\"format\"]/dayWidth[@type=\"wide\"]/day[@type=\"sat\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"1\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"2\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"3\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"4\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"5\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"6\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"7\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"8\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"9\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"10\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"11\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"abbreviated\"]/month[@type=\"12\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"1\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"2\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"3\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"4\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"5\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"6\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"7\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"8\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"9\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"10\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"11\"]", Level.POSIX);
    posixCoverage.put("//ldml/dates/calendars/calendar[@type=\"gregorian\"]/months/monthContext[@type=\"format\"]/monthWidth[@type=\"wide\"]/month[@type=\"12\"]", Level.POSIX);
    //posixCoverage.put("//ldml/collations/collation[@type=\"standard\"]/settings", Level.POSIX);
    //posixCoverage.put("//ldml/collations/collation[@type=\"standard\"]/rules", Level.POSIX);
  }
  
  private void initMetazoneCoverage(CLDRFile supplementalData) {
    String metazone_prefix = "//ldml/dates/timeZoneNames/metazone";

    metazone_level.clear();

    /* Tier 1 - Most populous and commonly used metazones - Basic coverage */

    String [][] Basic_Metazones = {
       { "America_Eastern", "generic" },
       { "America_Central", "generic" },
       { "America_Mountain", "generic" },
       { "America_Pacific", "generic" },
       { "Atlantic", "generic" },
       { "Europe_Central", "daylight" },
       { "Europe_Eastern", "daylight" },
       { "Europe_Western", "daylight" },
       { "GMT", "standard" }};
    
    for ( int i = 0 ; i < Basic_Metazones.length ; i++ ) {
      metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/long/standard", Level.BASIC);
      metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/short/standard", Level.BASIC);
      if ( Basic_Metazones[i][1].equals("generic") || Basic_Metazones[i][1].equals("daylight")) {
        metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/long/daylight", Level.BASIC);
        metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/short/daylight", Level.BASIC);
        if ( Basic_Metazones[i][1].equals("generic")) {
          metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/long/generic", Level.BASIC);
          metazone_level.put(metazone_prefix+"[@type=\""+Basic_Metazones[i][0]+"\"]/short/generic", Level.BASIC);
        }
      }
    } 

    /* Tier 2 - Moderate Coverage level for metazones */

    String [][] Moderate_Metazones = {
       { "Africa_Central", "standard" },
       { "Africa_Eastern", "standard" },
       { "Africa_Southern", "standard" },
       { "Africa_Western", "daylight" },
       { "Arabian", "generic" },
       { "China", "standard" },
       { "India", "standard" },
       { "Israel", "daylight" },
       { "Japan", "standard" },
       { "Korea", "standard" }};

    for ( int i = 0 ; i < Moderate_Metazones.length ; i++ ) {
      metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/long/standard", Level.MODERATE);
      metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/short/standard", Level.MODERATE);
      if ( Moderate_Metazones[i][1].equals("generic") || Moderate_Metazones[i][1].equals("daylight")) {
        metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/long/daylight", Level.MODERATE);
        metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/short/daylight", Level.MODERATE);
        if ( Moderate_Metazones[i][1].equals("generic")) {
          metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/long/generic", Level.MODERATE);
          metazone_level.put(metazone_prefix+"[@type=\""+Moderate_Metazones[i][0]+"\"]/short/generic", Level.MODERATE);
        }
      }
    } 

    String [][] Modern_Metazones = {
       { "Acre", "daylight" },
       { "Amazon", "daylight" },
       { "Australia_Central", "generic" },
       { "Australia_Eastern", "generic" },
       { "Australia_Western", "generic" },
       { "Australia_CentralWestern", "daylight" },
       { "Brasilia", "daylight" },
       { "Hong_Kong", "daylight" },
       { "Indochina", "standard" },
       { "Indonesia_Central", "standard" },
       { "Indonesia_Eastern", "standard" },
       { "Indonesia_Western", "standard" },
       { "Moscow", "daylight" },
       { "New_Zealand", "generic" }};

    for ( int i = 0 ; i < Modern_Metazones.length ; i++ ) {
      metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/long/standard", Level.MODERN);
      metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/short/standard", Level.MODERN);
      if ( Modern_Metazones[i][1].equals("generic") || Modern_Metazones[i][1].equals("daylight")) {
        metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/long/daylight", Level.MODERN);
        metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/short/daylight", Level.MODERN);
        if ( Modern_Metazones[i][1].equals("generic")) {
          metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/long/generic", Level.MODERN);
          metazone_level.put(metazone_prefix+"[@type=\""+Modern_Metazones[i][0]+"\"]/short/generic", Level.MODERN);
        }
      }
    } 
    /* 4th Tier - Comprehensive Level */

    String [][] Comprehensive_Metazones = {
       { "Afghanistan", "daylight" },
       { "Alaska", "generic" },
       { "Anadyr", "daylight" },
       { "Argentina", "daylight" },
       { "Armenia", "daylight" },
       { "Azerbaijan", "daylight" },
       { "Azores", "daylight" },
       { "Bangladesh", "standard" },
       { "Bhutan", "standard" },
       { "Bolivia", "standard" },
       { "Brunei", "standard" },
       { "Cape_Verde", "daylight" },
       { "Chamorro", "standard" },
       { "Chatham", "daylight" },
       { "Chile", "daylight" },
       { "Choibalsan", "daylight" },
       { "Christmas", "standard" },
       { "Cocos", "standard" },
       { "Colombia", "daylight" },
       { "Cook", "standard" },
       { "Cuba", "daylight" },
       { "Davis", "standard" },
       { "DumontDUrville", "standard" },
       { "East_Timor", "standard" },
       { "Easter", "daylight" },
       { "Ecuador", "standard" },
       { "Falkland", "daylight" },
       { "Fiji", "daylight" },
       { "French_Guiana", "standard" },
       { "French_Southern", "standard" },
       { "Galapagos", "standard" },
       { "Gambier", "standard" },
       { "Georgia", "daylight" },
       { "Gilbert_Islands", "standard" },
       { "Greenland_Eastern", "daylight" },
       { "Greenland_Western", "daylight" },
       { "Guam", "standard" },
       { "Guyana", "standard" },
       { "Hawaii_Aleutian", "generic" },
       { "Hawaii", "standard" },
       { "Hovd", "daylight" },
       { "Indian_Ocean", "standard" },
       { "Iran", "daylight" },
       { "Irkutsk", "daylight" },
       { "Kamchatka", "daylight" },
       { "Kazakhstan_Eastern", "standard" },
       { "Kazakhstan_Western", "standard" },
       { "Kosrae", "standard" },
       { "Krasnoyarsk", "daylight" },
       { "Kyrgystan", "standard" },
       { "Line_Islands", "standard" },
       { "Lord_Howe", "daylight" },
       { "Magadan", "daylight" },
       { "Malaysia", "daylight" },
       { "Maldives", "daylight" },
       { "Marquesas", "daylight" },
       { "Marshall_Islands", "standard" },
       { "Mauritius", "standard" },
       { "Mawson", "standard" },
       { "Mongolia", "daylight" },
       { "Nauru", "standard" },
       { "Nepal", "standard" },
       { "Newfoundland", "generic" },
       { "New_Caledonia", "daylight" },
       { "Niue", "standard" },
       { "Norfolk", "standard" },
       { "Noronha", "daylight" },
       { "North_Mariana", "standard" },
       { "Novosibirsk", "daylight" },
       { "Omsk", "daylight" },
       { "Pakistan", "daylight" },
       { "Palau", "standard" },
       { "Papua_New_Guinea", "standard" },
       { "Paraguay", "daylight" },
       { "Peru", "daylight" },
       { "Philippines", "daylight" },
       { "Phoenix_Islands", "standard" },
       { "Pierre_Miquelon", "daylight" },
       { "Pitcairn", "standard" },
       { "Ponape", "standard" },
       { "Reunion", "standard" },
       { "Rothera", "standard" },
       { "Sakhalin", "daylight" },
       { "Samara", "daylight" },
       { "Samoa", "standard" },
       { "Seychelles", "standard" },
       { "Singapore", "standard" },
       { "Solomon", "standard" },
       { "South_Georgia", "standard" },
       { "Suriname", "standard" },
       { "Syowa", "standard" },
       { "Tahiti", "standard" },
       { "Tajikistan", "standard" },
       { "Tonga", "daylight" },
       { "Truk", "standard" },
       { "Turkmenistan", "daylight" },
       { "Tuvalu", "standard" },
       { "Uruguay", "daylight" },
       { "Uzbekistan", "daylight" },
       { "Vanuatu", "daylight" },
       { "Venezuela", "standard" },
       { "Vladivostok", "daylight" },
       { "Volgograd", "daylight" },
       { "Vostok", "standard" },
       { "Wake", "standard" },
       { "Wallis", "standard" },
       { "Yakutsk", "daylight" },
       { "Yekaterinburg", "daylight" }};

    for ( int i = 0 ; i < Comprehensive_Metazones.length ; i++ ) {
      metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/long/standard", Level.COMPREHENSIVE);
      metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/short/standard", Level.COMPREHENSIVE);
      if ( Comprehensive_Metazones[i][1].equals("generic") || Comprehensive_Metazones[i][1].equals("daylight")) {
        metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/long/daylight", Level.COMPREHENSIVE);
        metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/short/daylight", Level.COMPREHENSIVE);
        if ( Comprehensive_Metazones[i][1].equals("generic")) {
          metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/long/generic", Level.COMPREHENSIVE);
          metazone_level.put(metazone_prefix+"[@type=\""+Comprehensive_Metazones[i][0]+"\"]/short/generic", Level.COMPREHENSIVE);
        }
      }
    } 
  }

  private static void getMetadata(CLDRFile metadata) {
    XPathParts parts = new XPathParts();
    for (Iterator it = metadata.iterator(); it.hasNext();) {
      String path = (String) it.next();
      path = metadata.getFullXPath(path);
      parts.set(path);
      String lastElement = parts.getElement(-1);
      //Map attributes = parts.getAttributes(-1);
      String type = parts.getAttributeValue(-1, "type");
      if (parts.containsElement("coverageAdditions")) {
        // System.out.println(path);
        // System.out.flush();
        //String value = metadata.getStringValue(path);
        // <languageCoverage type="basic" values="de en es fr it ja
        // pt ru zh"/>
        CoverageLevel.Level level = CoverageLevel.Level.get(type);
        String values = parts.getAttributeValue(-1, "values");
        Utility.addTreeMapChain(coverageData, 
            lastElement, level,
            new TreeSet(Arrays.asList(values.split("\\s+"))));
      }
    }
  }
  
  private static Set multizoneTerritories = null;
  
  private static void getData(CLDRFile data) {
    String lastCurrencyYear = Integer.toString(1900 + new Date().getYear() - 2);
    
    XPathParts parts = new XPathParts();

    // optimization -- don't get the paths in sorted order.
    // data.iterator(null, CLDRFile.ldmlComparator)
    for (Iterator it = data.iterator(); it.hasNext();) {
      String path = (String) it.next();
      //String value = metadata.getStringValue(path);
      path = data.getFullXPath(path);
      parts.set(path);
      String lastElement = parts.getElement(-1);
      //Map attributes = parts.getAttributes(-1);
      String type = parts.getAttributeValue(-1, "type");
      //System.out.println(path);
      if (lastElement.equals("zoneItem")) {
        if (multizoneTerritories == null) {
          //Map multiAttributes = parts.getAttributes(-2);
          String multizone = parts.getAttributeValue(-2, "multizone");
          multizoneTerritories = new TreeSet(Arrays.asList(multizone.split("\\s+")));
        }
        //<zoneItem type="Africa/Abidjan" territory="CI"/>
        String territory = parts.getAttributeValue(-1, "territory");
        if (!multizoneTerritories.contains(territory)) continue;
        Set territories = (Set) territory_timezone.get(territory);
        if (territories == null) territory_timezone.put(territory, territories = new TreeSet());
        territories.add(type);
      } else if (parts.containsElement("calendarData")) {
        // System.out.println(path);
        // System.out.flush();
        // we have element, type, subtype, and values
        Set values = new TreeSet(
            Arrays.asList((parts.getAttributeValue(-1, "territories")).split("\\s+")));
        Utility.addTreeMapChain(coverageData, 
            lastElement, type, values);
        addAllToCollectionValue(territory_calendar,values,type,TreeSet.class);
      } else if (parts.containsElement("languageData")) {
        // <language type="ab" scripts="Cyrl" territories="GE"
        // alt="secondary"/>
        String alt = parts.getAttributeValue(-1, "alt");
        if (alt != null) continue;
        modernLanguages.add(type);
        String scripts = parts.getAttributeValue(-1, "scripts");
        if (scripts != null) {
          Set scriptSet = new TreeSet(Arrays.asList(scripts
              .split("\\s+")));
          modernScripts.addAll(scriptSet);
          Utility.addTreeMapChain(language_scripts, type, scriptSet);
        }
        String territories = parts.getAttributeValue(-1, "territories");
        if (territories != null) {
          Set territorySet = new TreeSet(Arrays
              .asList(territories
                  .split("\\s+")));
          modernTerritories.addAll(territorySet);
          Utility.addTreeMapChain(language_territories, type, territorySet);
          addAllToCollectionValue(territory_languages, territorySet, type, ArrayList.class);
        }
      } else if (parts.containsElement("currencyData") && lastElement.equals("currency")) {
        //         <region iso3166="AM"><currency iso4217="AMD" from="1993-11-22"/>
        // if the 'to' value is less than 10 years, it is not modern
        String to = parts.getAttributeValue(-1, "to");
        String currency = parts.getAttributeValue(-1, "iso4217");
        if (to == null || to.compareTo(lastCurrencyYear) >= 0) {
          modernCurrencies.add(currency);
          // only add current currencies to must have list
          if (to == null) {
            String region = parts.getAttributes(-2).get("iso3166");
            Set currencies = (Set) territory_currency.get(region);
            if (currencies == null) territory_currency.put(region, currencies = new TreeSet());
            currencies.add(currency);
          }
        }
      } else if (parts.containsElement("territoryContainment")) {
        if (!type.equals("172")) {
          territoryContainment.add(type);
        }
        if (type.equals(EUROPEAN_UNION)) {
          euroCountries = new TreeSet(Arrays.asList((parts.getAttributeValue(-1, "contains")).split("\\s+")));
        }
      }
    }
    // filter out non-modern
    
//    Set<String> old = modernLanguages;
//    modernCurrencies = new TreeSet();
//    for (String language : old) {
//      Type type = Iso639Data.getType(language);
//      if (type == Type.Living) {
//        //System.out.println("*Adding " + language + "\t" + Iso639Data.getNames(language));
//        modernLanguages.add(language);
//      } else {
//        System.out.println("*Skipping " + language + "\t" + Iso639Data.getNames(language));
//      }
//    }
//    if (false) for (String language : Iso639Data.getAvailable()) {
//      if (old.contains(language)) continue;
//      if (Iso639Data.getSource(language) == Source.ISO_639_3) continue;
//      Type type = Iso639Data.getType(language);
//      if (type == Type.Living) {
//        System.out.println("**Living " + language + "\t" + Iso639Data.getNames(language));
//      } else {
//        System.out.println("**Nonliving " + language + "\t" + Iso639Data.getNames(language));
//      }
//    }
    
    if(euroCountries == null) {
      euroCountries = new TreeSet(); // placate other parts of the code
      euroCountriesMissing = true;
    }    
  }
  public void checkPosixCoverage(String path, String fullPath, String value,
      Map options, List result, CLDRFile file, CLDRFile resolved) {
    if (options.get("submission") == null) return;
    
    // skip all items that are in anything but raw codes
    String source = resolved.getSourceLocaleID(path, null);
    if (!source.equals(XMLSource.CODE_FALLBACK_ID) && !(source.equals("root") && isValueCode(fullPath, value))){
      return;
    }
    
    if(path == null) { 
      throw new InternalError("Empty path!");
    } else if(file == null) {
      throw new InternalError("no file to check!");
    }
    //parts.set(fullPath);
    //parts.equals()
    // check to see if the level is good enough
    CoverageLevel.Level level = (CoverageLevel.Level) posixCoverage.get(fullPath);
    
    if (level==null || level == CoverageLevel.Level.UNDETERMINED) return; // continue if we don't know what the status is
    if (Level.POSIX.compareTo(level) >= 0) {
      result.add(new CheckStatus().setType(CheckStatus.errorType)
          .setMessage("Needed to meet {0} coverage level.", new Object[] { level }));
    }
  }
  private boolean isValueCode(String xpath, String value){
    try{
      Integer.parseInt(value);
      if(xpath.indexOf("nativeZeroDigit")>0){
        return false;
      }
      return true;
    }catch(NumberFormatException ex){
      
    }
    return false;
  }
}
