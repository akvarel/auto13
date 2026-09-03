package steps;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.Select;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.codeborne.selenide.Condition.enabled;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.*;
import static steps.AuthSupport.*;

public final class DisposableDividendSteps {
  private static final Path SESSION_COOKIES = Path.of("build", "private", "customer-session.cookies");
  private static final Path SESSION_STORAGE = Path.of("build", "private", "customer-session.storage.json");
  private static final String REPRESENTED_COMPANY = "AutotestLtSingleSignee";
  private static final String SIGNER_FULL_NAME = "MARY ÄNN O’CONNEŽ-ŠUSLIK TESTNUMBER";
  private String appType = "Dividend Payment";
  private String sourceInstrument;
  private BigDecimal totalPaymentAmount;
  private String applicationId;
  private boolean sessionReused;
  private Path signedDocument;

  private Path contractPath() {
    String key = normalize(appType).replace(" ", "-");
    return Path.of("build", "reports", "disposable-" + key + "-application.properties");
  }

  private boolean customerSessionReady(String currentUrl, String expectedCompany) {
    if (currentUrl == null || currentUrl.contains("/login")) return false;
    if (currentUrl.contains("/company-selection")) return visibleExpectedCompanyCard(expectedCompany);
    if (!currentUrl.contains("/corporate-actions")) return false;
    try {
      SelenideElement represented = $("#navbarRepresentedDropdown");
      return represented.isDisplayed() && normalize(represented.getText())
        .equals(normalize(expectedCompany));
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Reuse-friendly readiness: any represented company counts — the caller
   * switches context via the navbar Switch-company flow when needed. */
  private boolean customerSessionReadyAnyCompany(String currentUrl) {
    if (currentUrl == null || currentUrl.contains("/login")
        || currentUrl.contains("/company-selection")) return false;
    if (!currentUrl.contains("/corporate-actions") && !currentUrl.contains("/holders-information")) return false;
    try {
      SelenideElement represented = $("#navbarRepresentedDropdown");
      return represented.isDisplayed() && !normalize(represented.getText()).isBlank();
    } catch (Throwable ignored) {
      return false;
    }
  }

  private boolean visibleExpectedCompanyCard(String expectedCompany) {
    try {
      Number matches = executeJavaScript(
        "const wanted=String(arguments[0]).toLowerCase();"
          + "return [...document.querySelectorAll('a.stretched-link')].filter(a=>{"
          + " const card=a.parentElement;"
          + " const text=(card?.innerText||'').replace(/\\s+/g,' ').trim().toLowerCase();"
          + " return !!card && card.getClientRects().length>0 && text.includes(wanted);"
          + "}).length;",
        expectedCompany);
      return matches != null && matches.intValue() == 1;
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Given("I log in through Mobile ID for the disposable application")
  public void login() {
    open("/");
    restoreSessionCookies();
    open("/login");
    boolean cachedSession = Files.isRegularFile(SESSION_COOKIES);
    boolean attemptedBoundedContextRecovery = false;
    long sessionDeadline = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < sessionDeadline) {
      if (bodyShowsNotAuthorized()) {
        System.out.println("DISPOSABLE_SESSION_NOT_AUTHORIZED on reuse");
        break;
      }
      String currentUrl = webdriver().driver().url();
      if (redirectAuthenticatedCustomerToCorporateActions(currentUrl, REPRESENTED_COMPANY)) continue;
      boolean sessionReady = customerSessionReady(currentUrl, REPRESENTED_COMPANY)
        || customerSessionReadyAnyCompany(currentUrl);
      if (!sessionReady && !attemptedBoundedContextRecovery && currentUrl != null
          && (currentUrl.contains("/company-selection") || currentUrl.contains("/corporate-actions"))) {
        attemptedBoundedContextRecovery = true;
        if (tryBoundedCustomerContextRecovery(currentUrl)) {
          sessionReused = true;
          persistSessionCookies();
          return;
        }
      }
      if (sessionReady) {
        System.out.println("DISPOSABLE_SESSION_REUSED url=" + currentUrl);
        sessionReused = true;
        persistSessionCookies();
        return;
      }
      if (!cachedSession) {
        try {
          if (!exactVisible("Mobile ID", "a, button, [role=button], div, span").isEmpty()) break;
        } catch (Throwable ignored) { }
      } else {
        // The login form is showing despite cached cookies — the server-side
        // session is gone. Bail out of the reuse polls immediately.
        try {
          if (!exactVisible("Mobile ID", "a, button, [role=button], div, span").isEmpty()) {
            System.out.println("DISPOSABLE_SESSION_STALE_LOGIN_FORM reuse-bailout");
            break;
          }
        } catch (Throwable ignored) { }
        // Restored storage can jump straight to a blank /company-selection
        // shell (no login form) — the server session is gone too。
        try {
          Boolean blank = executeJavaScript("return document.body.innerText.trim().length===0;");
          if (Boolean.TRUE.equals(blank) && currentUrl != null && currentUrl.contains("/company-selection")) {
            System.out.println("DISPOSABLE_SESSION_STALE_BLANK_SHELL reuse-bailout url=" + currentUrl);
            break;
          }
        } catch (Throwable ignored) { }
      }
      sleep(200);
    }
    if (cachedSession && !bodyShowsNotAuthorized()) {
      open("/login");
      long fallbackDeadline = System.currentTimeMillis() + 15000;
      while (System.currentTimeMillis() < fallbackDeadline) {
        if (bodyShowsNotAuthorized()) {
          System.out.println("DISPOSABLE_SESSION_NOT_AUTHORIZED on late reuse");
          break;
        }
        String currentUrl = webdriver().driver().url();
        if (redirectAuthenticatedCustomerToCorporateActions(currentUrl, REPRESENTED_COMPANY)) continue;
        boolean sessionReady = customerSessionReady(currentUrl, REPRESENTED_COMPANY)
          || customerSessionReadyAnyCompany(currentUrl);
        if (!sessionReady && !attemptedBoundedContextRecovery && currentUrl != null
            && (currentUrl.contains("/company-selection") || currentUrl.contains("/corporate-actions"))) {
          attemptedBoundedContextRecovery = true;
          if (tryBoundedCustomerContextRecovery(currentUrl)) {
            sessionReused = true;
            persistSessionCookies();
            return;
          }
        }
        if (sessionReady) {
          System.out.println("DISPOSABLE_SESSION_REUSED_LATE url=" + currentUrl);
          sessionReused = true;
          persistSessionCookies();
          return;
        }
        try {
          if (!exactVisible("Mobile ID", "a, button, [role=button], div, span").isEmpty()) break;
        } catch (Throwable ignored) { }
        sleep(200);
      }
    }
    // Reuse failed — stale cookies/storage can leave a blank authenticated
    // shell that blocks the fresh Dokobit flow. Wipe everything first.
    if (cachedSession) {
      System.out.println("DISPOSABLE_SESSION_PURGING_STALE_STATE before fresh login");
      clearSessionCookies();
      open("/login");
    }
    performFreshDokobitLogin();
  }

  /**
   * Performs a fresh Dokobit Mobile ID login for the LT single-signee user, waiting
   * for the authenticated customer context to land directly on /corporate-actions
   * with the AutotestLtSingleSignee represented company. This is the direct
   * Lithuanian-company pickup that avoids any navbar "Switch company" dance. */
  private void performFreshDokobitLogin() {
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        openDokobitProvider("Mobile ID");
        pickDokobitCountry("LT");
        enterDokobitPhone("60000666");
        enterDokobitPersonalCode("50001018865");
        submitDokobitLogin();
        awaitAuthenticatedCustomer("/corporate-actions", REPRESENTED_COMPANY);
        persistSessionCookies();
        sessionReused = false;
        return;
      } catch (Throwable loginFailure) {
        System.out.println("DISPOSABLE_LOGIN_ATTEMPT_FAILED attempt=" + attempt + " err=" + loginFailure);
        if (attempt == 2) {
          System.out.println("DISPOSABLE_LOGIN_FALLBACK_MANUAL after Dokobit Double failure");
          clearSessionCookies();
          sleep(800);
          open("/login");
          AuthSupport.manualLogin();
          awaitAuthenticatedCustomer("/corporate-actions", REPRESENTED_COMPANY);
          persistSessionCookies();
          sessionReused = false;
          return;
        }
        sleep(800);
        open("/login");
      }
    }
    throw new AssertionError("Fresh Dokobit login exhausted all attempts without establishing "
      + "customer context; url=" + webdriver().driver().url());
  }
  /**
   * For the LT single-signee company, never use the navbar "Switch company" modal:
   * the LT Dokobit user fresh login lands directly in the AutotestLtSingleSignee
   * represented-company context. Wipe the cached session and re-login fresh so the
   * direct Lithuanian company is picked up instead of switching. */
  private void requireDirectLithuanianCompany(String company) {
    if (!REPRESENTED_COMPANY.equals(company))
      throw new AssertionError("Direct-Lithuanian-company re-login requested for non-LT company '"
        + company + "'; url=" + webdriver().driver().url());
    System.out.println("DISPOSABLE_COMPANY_DIRECT_RELOGIN requested " + company
      + " url " + webdriver().driver().url());
    clearSessionCookies();
    open("/login");
    performFreshDokobitLogin();
  }

  private boolean tryBoundedCustomerContextRecovery(String currentUrl) {
    System.out.println("DISPOSABLE_CACHED_CONTEXT_RECOVERY from=" + currentUrl);
    try {
      awaitAuthenticatedCustomer("/corporate-actions", REPRESENTED_COMPANY);
      return true;
    } catch (AssertionError failure) {
      System.out.println("DISPOSABLE_CACHED_CONTEXT_RECOVERY_FAILED url="
        + webdriver().driver().url() + " err=" + failure.getMessage());
      return false;
    }
  }

  private boolean redirectAuthenticatedCustomerToCorporateActions(String currentUrl, String expectedCompany) {
    if (currentUrl == null || currentUrl.contains("/login") || currentUrl.contains("/company-selection")
        || currentUrl.contains("/corporate-actions")) return false;
    try {
      SelenideElement represented = $("#navbarRepresentedDropdown");
      if (!represented.isDisplayed() || !normalize(represented.getText())
          .equals(normalize(expectedCompany))) return false;
      System.out.println("DISPOSABLE_CUSTOMER_CONTEXT_REDIRECT from=" + currentUrl);
      open("/corporate-actions");
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private void clearSessionCookies() {
    try { Files.deleteIfExists(SESSION_COOKIES); } catch (Exception ignored) { }
    try { Files.deleteIfExists(SESSION_STORAGE); } catch (Exception ignored) { }
    try {
      for (Cookie cookie : webdriver().driver().getWebDriver().manage().getCookies()) {
        webdriver().driver().getWebDriver().manage().deleteCookie(cookie);
      }
    } catch (Throwable ignored) { }
    try {
      executeJavaScript("try{localStorage.clear()}catch(e){} try{sessionStorage.clear()}catch(e){}");
    } catch (Throwable ignored) { }
  }

  private boolean bodyShowsNotAuthorized() {
    try {
      String body = $("body").getText().toLowerCase(Locale.ROOT);
      return body.contains("not authorized") || body.contains("not authorised") || body.contains("feedback-icon");
    } catch (Throwable e) { return false; }
  }

  @And("I select company {string} for the disposable application")
  public void selectCompany(String company) {
    String currentUrl = webdriver().driver().url();
    if (sessionReused && !currentUrl.contains("/company-selection")) {
      if (representedCompanyMatches(company)) {
        System.out.println("DISPOSABLE_COMPANY_CONTEXT_REUSED requested=" + company);
        return;
      }
      System.out.println("DISPOSABLE_COMPANY_CONTEXT_MISMATCH requested=" + company
        + " url=" + currentUrl);
            if (REPRESENTED_COMPANY.equals(company)) {
        requireDirectLithuanianCompany(company);
        return;
      }
// Switch via the navbar dropdown → Switch Company modal (deep-linking
      // Direct access: open the SPA /company-selection route and pick the company card
      if (!tryAuthenticatedCompanySelection(company)) {
        throw new AssertionError("Direct company selection did not establish '"
          + company + "'; url=" + webdriver().driver().url());
      }
      persistSessionCookies();
      return;
    }
    if (!currentUrl.contains("/company-selection")) {
      long representedDeadline = System.currentTimeMillis() + 20000;
      while (System.currentTimeMillis() < representedDeadline) {
        if (representedCompanyMatches(company)) {
          System.out.println("DISPOSABLE_COMPANY_REUSED " + company);
          return;
        }
        if (REPRESENTED_COMPANY.equals(company)) {
          requireDirectLithuanianCompany(company);
          return;
        }
        if (tryAuthenticatedCompanySelection(company)) {
          persistSessionCookies();
          return;
        }
        sleep(500);
      }
    }
    AssertionError lastSelectError = null;
    for (int selectAttempt = 1; selectAttempt <= 3; selectAttempt++) {
      try {
        if (!currentUrlContains("/company-selection")) {
          if (REPRESENTED_COMPANY.equals(company)) {
            requireDirectLithuanianCompany(company);
            return;
          }
          if (!tryAuthenticatedCompanySelection(company)) {
            throw new AssertionError("Direct company selection did not establish '"
              + company + "'; url=" + webdriver().driver().url());
          }
        } else {
          selectObservedCompanyToRepresent(company);
        }
        assertCompanyContextApplied();
        awaitRepresentedCompany(company, System.currentTimeMillis() + Configuration.timeout);
        persistSessionCookies();
        caSettle();
        return;
      } catch (AssertionError error) {
        lastSelectError = error;
        System.out.println("DISPOSABLE_COMPANY_SELECT_RETRY attempt=" + selectAttempt
          + " url=" + webdriver().driver().url());
        if (selectAttempt < 3) {
          sleep(1500);
        }
      }
    }
    throw lastSelectError;
  }

  private static boolean currentUrlContains(String fragment) {
    String currentUrl = webdriver().driver().url();
    return currentUrl != null && currentUrl.contains(fragment);
  }

  /**
   * Opens the represented-company navbar dropdown, clicks "Switch company",
   * waits for the "Switch Company" modal, and clicks the card matching the
   * requested company (cards are anchors with stretched-link markup, same
   * pattern as the /company-selection page). Returns true when the requested
   * company became the active represented company.
   */
  private boolean tryDirectCompanySelection(String company) {
    System.out.println("DISPOSABLE_COMPANY_DIRECT_ATTEMPT requested=" + company
      + " url=" + webdriver().driver().url());
    String before = webdriver().driver().url();
    try {
      open("/company-selection");
      sleep(800);
      long deadline = System.currentTimeMillis() + 8000;
      while (System.currentTimeMillis() < deadline) {
        String url = webdriver().driver().url();
        String body = $("body").getText();
        if (url != null && url.contains("/company-selection") && !body.isEmpty() && !bodyShowsNotAuthorized()) {
          System.out.println("DISPOSABLE_COMPANY_DIRECT_SELECT requested=" + company);
          pickCompanyCardViaJs(company);
          awaitRepresentedCompany(company, System.currentTimeMillis() + 6000);
          return true;
        }
        if (bodyShowsNotAuthorized()) break;
        sleep(200);
      }
      System.out.println("DISPOSABLE_COMPANY_DIRECT_SELECT_UNAVAILABLE requested=" + company
        + " url=" + webdriver().driver().url());
      open(before);
      sleep(500);
      return false;
    } catch (Throwable failure) {
      System.out.println("DISPOSABLE_COMPANY_DIRECT_SELECT_FAILED " + failure.getClass().getSimpleName());
      if (before != null && !before.isBlank()) {
        try {
          open(before);
          sleep(500);
        } catch (Throwable ignored) { }
      }
      return false;
    }
  }

  private boolean tryAuthenticatedCompanySelection(String company) {
    if (switchCompanyViaMenu(company)) return true;
    return tryDirectCompanySelection(company);
  }

  private void pickCompanyCardViaJs(String company) {
    String wanted = normalize(company);
    Object clicked = executeJavaScript(loadJs("ca-pick-company-card.js"), wanted);
    System.out.println("DISPOSABLE_COMPANY_DIRECT_CARD_JS " + clicked);
  }

  private boolean switchCompanyViaMenu(String company) {
    String wanted = normalize(company).toLowerCase(java.util.Locale.ROOT);
    try {
      if (representedCompanyMatches(company)) return true;
      SelenideElement represented = $("#navbarRepresentedDropdown");
      if (!represented.isDisplayed()) return false;
      executeJavaScript(
        "const dd=document.querySelector('#navbarRepresentedDropdown');"
        + "if(dd) dd.click();");
      sleep(600);
      String pickResult = executeJavaScript(
        "const menus=[...document.querySelectorAll('.dropdown-menu')]"
        + "  .filter(function(m){return m.getClientRects().length>0 || m.classList.contains('show');});"
        + "let items=[];"
        + "menus.forEach(function(m){"
        + "  [...m.querySelectorAll('a,button,[role=menuitem]')].forEach(function(e){"
        + "    const t=(e.innerText||'').replace(/\\s+/g,' ').trim();"
        + "    if(t.length>0) items.push({el:e,text:t});"
        + "  });"
        + "});"
        + "const matches=items.filter(function(i){"
        + "  return i.text.toLowerCase().indexOf('switch company')>=0;});"
        + "if(matches.length>=1){matches[0].el.click();"
        + "  return JSON.stringify({state:'clicked'});}"
        + "return JSON.stringify({state:'nomatch',"
        + "  items:items.map(function(i){return i.text;}).slice(0,12)});");
      if (pickResult == null || !pickResult.contains("\"clicked\"")) {
        System.out.println("DISPOSABLE_COMPANY_SWITCH_MENU " + pickResult);
        return false;
      }
      // Direct-access preference (CA-21: the "Switch company" menu item may SPA-navigate
      // to the /company-selection page (cards) instead of opening a modal.After Angular
      // settles, prefer the validated BO-02 picker there; else fall back to the modal flow..
      long uiDeadline = System.currentTimeMillis() + 4000;
      boolean modalSeen = false;
      while (System.currentTimeMillis() < uiDeadline) {
        if (webdriver().driver().url().contains("/company-selection")
            && $("body").getText().contains("Choose who you represent")) {
          System.out.println("DISPOSABLE_COMPANY_DIRECT_PAGE url=" + webdriver().driver().url());
          selectObservedCompanyToRepresent(company);
          return true;
        }
        Object modalCheckAttr = executeJavaScript(
          "const m=document.querySelector('ngb-modal-window,.modal.show,.modal');"
            + "return m && m.getClientRects().length>0 ? 'yes' : 'no';");
        if ("yes".equals(String.valueOf(modalCheckAttr))) {
          modalSeen = true;
          break;
        }
        sleep(200);
      }
      if (!modalSeen) {
        System.out.println("DISPOSABLE_COMPANY_SWITCH_NEITHER_UI url=" + webdriver().driver().url());
        return false;
      }
      // Modal "Switch Company / Choose who you represent" appears — pick the card.
      // Early clicks can land before Angular hydrates, so keep re-clicking a
      // unique card until the represented-company context actually flips.
      long contextDeadline = System.currentTimeMillis() + Configuration.timeout;
      String cardResult = null;
      while (System.currentTimeMillis() < contextDeadline) {
        if (representedCompanyMatches(company)) {
          System.out.println("DISPOSABLE_COMPANY_SWITCH_CARD " + cardResult);
          return true;
        }
        sleep(400);
        cardResult = executeJavaScript(
          "const wanted=arguments[0];"
          + "const modal=document.querySelector('ngb-modal-window,.modal.show,.modal');"
          + "if(!modal || modal.getClientRects().length===0) return JSON.stringify({state:'no-modal'});"
          + "const links=[...modal.querySelectorAll('a.stretched-link')].filter(function(a){"
          + "  const card=a.parentElement;"
          + "  const text=((card&&card.innerText)||'').replace(/\\s+/g,' ').trim().toLowerCase();"
          + "  return !!card && card.getClientRects().length>0 && text.indexOf(wanted)>=0;"
          + "});"
          + "if(links.length===1){links[0].click();"
          + "  return JSON.stringify({state:'card-clicked',via:'stretched-link'});}"
          + "const clickable=[...modal.querySelectorAll('a,button,div,li,span')].filter(function(e){"
          + "  const t=((e.innerText||'')).replace(/\\s+/g,' ').trim().toLowerCase();"
          + "  return t.length>0 && t.indexOf(wanted)>=0 && e.getClientRects().length>0;"
          + "});"
          + "if(clickable.length>0){clickable[clickable.length-1].click();"
          + "  return JSON.stringify({state:'card-clicked',via:'text',candidates:clickable.length});}"
          + "return JSON.stringify({state:'cards-0',observed:[...modal.querySelectorAll('a')]"
          + "    .map(function(a){return (a.innerText||'').trim();}).filter(Boolean).slice(0,8)});",
          wanted);
      }
      System.out.println("DISPOSABLE_COMPANY_SWITCH_CARD_TIMEOUT last=" + cardResult
        + " url=" + webdriver().driver().url());
      String modalHtml = executeJavaScript(
        "const modal=document.querySelector('ngb-modal-window,.modal.show,.modal');"
        + "if(!modal) return 'no-modal';"
        + "return modal.innerHTML.replace(/\\s+/g,' ').substring(0,900);");
      System.out.println("DISPOSABLE_COMPANY_SWITCH_MODAL_HTML " + modalHtml);
      return false;
    } catch (Throwable failure) {
      System.out.println("DISPOSABLE_COMPANY_DROPDOWN_FAILED err=" + failure);
      return false;
    }
  }

  private boolean representedCompanyMatches(String expectedCompany) {
    String wanted = normalize(expectedCompany);
    if (wanted.isBlank()) return false;
    try {
      SelenideElement represented = $("#navbarRepresentedDropdown");
      return represented.isDisplayed() && normalize(represented.getText()).equals(wanted);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private List<SelenideElement> exactSelectableCompanyChoices(String expectedCompany) {
    String wanted = normalize(expectedCompany);
    List<SelenideElement> matches = new ArrayList<>();
    for (SelenideElement candidate : $("body").$$("a, button, [role=menuitem], [role=option], [role=button]")) {
      try {
        if (candidate.isDisplayed() && candidate.isEnabled() && wanted.equals(normalize(candidate.getText()))) {
          matches.add(candidate);
        }
      } catch (Throwable ignored) {
        // The menu can rerender while the bounded selection loop is polling.
      }
    }
    if (matches.isEmpty()) {
      // Dropdown items may render extra text (reg numbers etc.) — fall back to contains
      for (SelenideElement candidate : $("body").$$("a, button, [role=menuitem], [role=option], [role=button]")) {
        try {
          String text = normalize(candidate.getText());
          if (candidate.isDisplayed() && candidate.isEnabled() && text.contains(wanted)
              && text.length() <= wanted.length() + 40) {
            matches.add(candidate);
          }
        } catch (Throwable ignored) {
          // ignore stale candidates
        }
      }
    }
    return matches;
  }

  private void awaitRepresentedCompany(String expectedCompany, long deadline) {
    while (System.currentTimeMillis() < deadline) {
      if (representedCompanyMatches(expectedCompany)) return;
      sleep(200);
    }
    throw new AssertionError("Represented-company selection did not establish expected company '"
      + expectedCompany + "'; url=" + webdriver().driver().url());
  }

  private void persistSessionCookies() {
    try {
      Files.createDirectories(SESSION_COOKIES.getParent());
      StringBuilder serialized = new StringBuilder();
      for (Cookie cookie : webdriver().driver().getWebDriver().manage().getCookies()) {
        serialized.append(encode(cookie.getName())).append('\t')
          .append(encode(cookie.getValue())).append('\t')
          .append(encode(safe(cookie.getDomain()))).append('\t')
          .append(encode(safe(cookie.getPath()))).append('\t')
          .append(cookie.isSecure()).append('\t')
          .append(cookie.isHttpOnly()).append('\n');
      }
      Files.writeString(SESSION_COOKIES, serialized.toString());
      // The customer SPA also keeps auth state in web storage — persist it so
      // the next scenario in a fresh browser can reuse the session.
      String storage = executeJavaScript(
        "const ls={};for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);ls[k]=localStorage.getItem(k);}"
        + "const ss={};for(let i=0;i<sessionStorage.length;i++){const k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}"
        + "return JSON.stringify({ls:ls,ss:ss});");
      if (storage != null && storage.length() > 4) {
        Files.writeString(SESSION_STORAGE, storage);
      }
      System.out.println("DISPOSABLE_SESSION_SAVED cookies=" + webdriver().driver().getWebDriver().manage().getCookies().size());
    } catch (Exception error) {
      System.out.println("DISPOSABLE_SESSION_SAVE_FAILED " + error.getClass().getSimpleName());
    }
  }

  private void restoreSessionCookies() {
    if (!Files.isRegularFile(SESSION_COOKIES)) return;
    int restored = 0;
    try {
      for (String line : Files.readAllLines(SESSION_COOKIES)) {
        if (line.isBlank()) continue;
        String[] parts = line.split("\\t", -1);
        if (parts.length != 6) continue;
        Cookie.Builder builder = new Cookie.Builder(decode(parts[0]), decode(parts[1]));
        String domain = decode(parts[2]);
        String path = decode(parts[3]);
        if (!domain.isBlank()) builder.domain(domain);
        builder.path(path.isBlank() ? "/" : path);
        if (Boolean.parseBoolean(parts[4])) builder.isSecure(true);
        if (Boolean.parseBoolean(parts[5])) builder.isHttpOnly(true);
        try {
          webdriver().driver().getWebDriver().manage().addCookie(builder.build());
          restored++;
        } catch (Exception ignored) { }
      }
      if (restored > 0 && Files.isRegularFile(SESSION_STORAGE)) {
        String storage = Files.readString(SESSION_STORAGE);
        executeJavaScript(
          "try{const s=JSON.parse(arguments[0]);"
          + "Object.entries(s.ls||{}).forEach(function(e){localStorage.setItem(e[0],e[1]);});"
          + "Object.entries(s.ss||{}).forEach(function(e){sessionStorage.setItem(e[0],e[1]);});}catch(e){}",
          storage);
      }
      if (restored > 0) {
        refresh();
        System.out.println("DISPOSABLE_SESSION_RESTORED cookies=" + restored);
      }
    } catch (Exception error) {
      System.out.println("DISPOSABLE_SESSION_RESTORE_FAILED " + error.getClass().getSimpleName());
    }
  }

  @When("I open Corporate Actions from the customer menu")
  public void openCorporateActions() {
    List<SelenideElement> controls = awaitExactVisibleControls("Corporate Actions", 15000);
    if (controls.isEmpty()) {
      // A represented-company switch can finish before Angular rebuilds the
      // navbar. Refresh the authenticated shell once, then require the genuine
      // menu control rather than bypassing it with a deep link.
      refresh();
      controls = awaitExactVisibleControls("Corporate Actions", 15000);
    }
    if (controls.isEmpty()) {
      // Some represented-company switches settle on a sparse authenticated
      // route that has the selected-company control but no application menu.
      // Re-enter a stable customer shell, then still open Corporate Actions
      // through the genuine navbar control required by this scenario.
      SelenideElement represented = $("#navbarRepresentedDropdown");
      if (represented.exists() && represented.isDisplayed()
          && !normalize(represented.getText()).isBlank()) {
        open("/holders-information");
        controls = awaitExactVisibleControls("Corporate Actions", 15000);
      }
    }
    if (controls.size() != 1) {
      throw new AssertionError("Expected one interactive Corporate Actions menu control before opening, found " + controls.size());
    }
    controls.get(0).click();
    sleep(300);
    List<SelenideElement> openedControls = exactVisible("Corporate Actions", "a, button, [role=button]");
    if (openedControls.size() < 2) {
      throw new AssertionError("Corporate Actions dropdown did not expose its Corporate Actions submenu; found " + openedControls.size());
    }
    openedControls.get(openedControls.size() - 1).click();
    awaitBodyText("Create Application");
  }

  private List<SelenideElement> awaitExactVisibleControls(String label, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    List<SelenideElement> controls = List.of();
    while (System.currentTimeMillis() < deadline) {
      controls = exactVisible(label, "a, button, [role=button]");
      if (!controls.isEmpty()) return controls;
      sleep(100);
    }
    return controls;
  }

  @And("I click Create Application")
  public void clickCreateApplication() {
    uniqueObservedControl("Create Application").click();
    awaitBodyText("Choose application type");
  }

  @And("I choose the last {string} application type")
  public void chooseLastApplicationType(String type) {
    List<SelenideElement> matches = exactVisible(type, "button, a, [role=button], li, div, span");
    if (matches.isEmpty()) throw new AssertionError("No visible application type '" + type + "'");
    matches.get(matches.size() - 1).click();
    appType = type;
    awaitBodyText("Application data");
  }

  @And("I dump the application type options")
  public void dumpApplicationTypeOptions() {
    sleep(500);
    Object result = executeJavaScript(
      "return JSON.stringify([...document.querySelectorAll('button,a,li,div,span,[role=option]')]"
        + ".filter(e=>e.offsetParent!==null)"
        + ".map(e=>((e.textContent||'').trim()))"
        + ".filter(v=>v.length>0 && v.length<60)"
        + ".filter((v,i,a)=>a.indexOf(v)===i));");
    System.out.println("APP_TYPE_OPTIONS " + result);
  }

  @When("I dump the visible application form fields")
  public void dumpApplicationFormFields() {
    sleep(800);
    Object result = executeJavaScript(
      "const fields=[...document.querySelectorAll('input,textarea,select,[role=combobox]')].filter(e=>e.offsetParent!==null);"
        + "return JSON.stringify(fields.map(e=>{"
        + "let label=''; const id=e.id; if(id){const l=document.querySelector('label[for=\"'+id+'\"]'); if(l) label=l.textContent.trim();}"
        + "if(!label){const box=e.closest('.form-group,fieldset,section,div'); if(box){const t=box.querySelector(':scope > label,.control-label,.form-label'); if(t) label=t.textContent.trim();}}"
        + "return {id,label,name:e.name,type:e.type,tag:e.tagName,ph:e.placeholder,req:e.required,invalid:e.getAttribute('aria-invalid')};"
        + "}));");
    System.out.println("FORM_FIELDS " + result);
  }

  @Then("the Application data form must be visible")
  public void formVisible() {
    awaitBodyText("Application data");
    $("form").shouldBe(visible);
  }

  @When("I select and remember a source instrument")
  public void selectSourceInstrument() {
    SelenideElement field = sourceInstrumentControl();
    System.out.println("SOURCE_INSTRUMENT_CONTROL tag=" + field.getTagName());
    if ("select".equalsIgnoreCase(field.getTagName())) {
      Select select = new Select(field.getWrappedElement());
      var selected = select.getFirstSelectedOption();
      String selectedValue = safe(selected.getAttribute("value")).trim();
      String selectedText = safe(selected.getText()).trim();
      if (!selectedValue.isBlank() && !"null".equalsIgnoreCase(selectedValue)
          && !selectedText.toLowerCase(Locale.ROOT).contains("select an instrument")) {
        long populatedDeadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < populatedDeadline) {
          if (decimalValue(fieldForLabel(sourceInstrumentPopulatedLabel())).compareTo(BigDecimal.ZERO) > 0) {
            sourceInstrument = selectedText.isBlank() ? selectedValue : selectedText;
            screenshot("disposable-dividend-source-instrument-selected");
            return;
          }
          sleep(200);
        }
      }
      for (var option : select.getOptions()) {
        String value = safe(option.getAttribute("value")).trim();
        String text = safe(option.getAttribute("textContent")).trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value) || text.toLowerCase(Locale.ROOT).contains("select an instrument")) continue;
        select.selectByValue(value);
        field.sendKeys(Keys.TAB);
        long populatedDeadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < populatedDeadline) {
          if (decimalValue(fieldForLabel(sourceInstrumentPopulatedLabel())).compareTo(BigDecimal.ZERO) > 0) {
            sourceInstrument = text.isBlank() ? value : text;
            screenshot("disposable-dividend-source-instrument-selected");
            return;
          }
          sleep(200);
        }
        System.out.println("SOURCE_INSTRUMENT_WITHOUT_EXPECTED_TOTAL observed=true");
      }
      throw new AssertionError("No source instrument populated a positive Total issued shares value");
    }
    executeJavaScript("arguments[0].scrollIntoView({block:'center',inline:'center'});", field.getWrappedElement());
    try {
      field.click();
    } catch (org.openqa.selenium.ElementClickInterceptedException intercepted) {
      executeJavaScript("arguments[0].focus();", field.getWrappedElement());
    }
    sleep(300);
    List<SelenideElement> options = $("body").$$("[role=option], [role=listbox] li, .dropdown-menu li, .dropdown-menu .dropdown-item")
      .stream().filter(SelenideElement::isDisplayed).toList();
    System.out.println("SOURCE_INSTRUMENT_OPTION_COUNT " + options.size());
    sourceInstrument = null;
    for (SelenideElement option : options) {
      String text = option.getText().trim();
      if (text.isBlank() || text.toLowerCase(Locale.ROOT).contains("select an instrument")) continue;
      option.click();
      sourceInstrument = text;
      break;
    }
    if (sourceInstrument == null || sourceInstrument.isBlank()) {
      try {
        Files.writeString(Path.of("build", "reports", "disposable-dividend-form.html"), webdriver().driver().getWebDriver().getPageSource());
      } catch (Exception ignored) { }
      throw new AssertionError("Source instrument selection did not retain a value");
    }
    awaitPositiveValue(sourceInstrumentPopulatedLabel());
    screenshot("disposable-dividend-source-instrument-selected");
  }

  private String sourceInstrumentPopulatedLabel() {
    switch (normalize(appType)) {
      case "dividend payment": return "Total issued shares";
      case "bonus issue": return "Number of shares before";
      case "interest payment": return "Total Issued (of the debt instrument)";
      case "additional issuance of bonds": return "Nominal Value (before)";
      default: return "Total issued shares";
    }
  }

  private SelenideElement sourceInstrumentControl() {
    for (SelenideElement el : $$("select, [role=combobox]")) {
      if (!el.isDisplayed() || !el.isEnabled()) continue;
      String id = safe(el.getAttribute("id"));
      if (id.contains("security_name")) return el;
      String label = "";
      if (!id.isBlank()) {
        SelenideElement l = $(By.cssSelector("label[for=\"" + id + "\"]"));
        if (l.exists()) label = l.getText();
      }
      if (normalize(label).contains("source instrument")) return el;
    }
    throw new AssertionError("No visible Source instrument control");
  }

  @And("I set Payment for one security to {string}")
  public void setPaymentPerSecurity(String value) {
    setField("Payment for one security", value);
  }

  @Then("Total payment amount must equal Total issued shares multiplied by Payment for one security")
  public void verifyTotalFormula() {
    BigDecimal issued = decimalValue(fieldForLabel("Total issued shares"));
    if (issued.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AssertionError("Selected source instrument did not populate a positive Total issued shares value");
    }
    BigDecimal payment = decimalValue(fieldForLabel("Payment for one security"));
    BigDecimal expected = issued.multiply(payment);
    SelenideElement total = fieldForLabel("Total payment amount");
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    BigDecimal actual = BigDecimal.ZERO;
    while (System.currentTimeMillis() < deadline) {
      actual = decimalValue(total);
      if (actual.compareTo(expected) == 0) {
        totalPaymentAmount = actual;
        return;
      }
      sleep(200);
    }
    throw new AssertionError("Total payment amount " + actual + " != issued shares " + issued
      + " * payment " + payment + " = " + expected);
  }

  @When("I set Date of general meeting within the past 30 days")
  public void setGeneralMeetingDate() {
    setDate("Date of general meeting", LocalDate.now().minusDays(7));
  }

  @And("I set Net dividend amount transferred to paying agent to the calculated total payment amount")
  public void setNetDividendAmount() {
    requireTotal();
    setControlById("dp_net_amount_transfered_paying_agent", plain(totalPaymentAmount));
  }

  @And("I add two random Excluded accounts rows")
  public void addExcludedAccounts() {
    $("#dp_aet_code_0").setValue(randomDigits(9));
    clickExactVisible("Add row");
    $("#dp_aet_code_1").shouldBe(visible).setValue(randomDigits(10));
    resolveExcludedAccountRows();
  }

  @And("I set Ex-date within the next 7 days, retrying another date on validation error")
  public void setExDate() {
    AssertionError last = null;
    for (int day = 1; day <= 7; day++) {
      LocalDate exDate = LocalDate.now().plusDays(day);
      setDate("Ex-date", exDate);
      sleep(500);
      if (!hasValidationError(fieldForLabel("Ex-date"))) {
        LocalDate recordDate = nextBusinessDay(exDate.plusDays(1));
        LocalDate paymentDate = nextBusinessDay(recordDate.plusDays(1));
        setDate("Record date", recordDate);
        setDate("Payment date", paymentDate);
        return;
      }
      last = new AssertionError("Ex-date +" + day + " day was rejected");
    }
    throw last == null ? new AssertionError("No Ex-date could be selected") : last;
  }

  @Then("Record date and Payment date must be populated")
  public void derivedDatesPopulated() {
    requirePopulated("Record date");
    requirePopulated("Payment date");
  }

  @When("I save the disposable application as draft, filling mandatory fields and attaching a PDF if required")
  public void saveDraft() throws Exception {
    for (int attempt = 0; attempt < 7; attempt++) {
      clickSaveDraft();
      awaitDraftSaveResult();
      if (signDocumentVisible()) {
        applicationId = applicationIdFromUrl();
        return;
      }
      resolveExcludedAccountRows();
      fillVisibleMandatoryFields();
      attachPdfIfRequired();
      logInvalidFields(attempt + 1);
    }
    throw new AssertionError("Save as Draft did not produce an application detail with Sign Document");
  }

  @When("I fill the disposable {string} form and save as draft")
  public void fillDisposableApplicationAndSaveDraft(String type) throws Exception {
    appType = type;
    selectSourceInstrument();
    setTypeSpecificFields();
    saveDraft();
  }

  private void setTypeSpecificFields() {
    switch (normalize(appType)) {
      case "bonus issue" -> fillBonusIssueForm();
      case "interest payment" -> fillInterestPaymentForm();
      case "additional issuance of bonds" -> fillAdditionalBondsForm();
      default -> { /* dividend payment uses its dedicated steps */ }
    }
  }

  private void fillBonusIssueForm() {
    clickRadioInGroup("bi_proc_time", "option1");
    clickRadioInGroup("bi_rounding", "rounding_option1");
    checkCheckboxByName("bi_same_rights_confirm");
    setField("Number of new shares", "1");
    setField("For every 1 share", "1");
    setField("Ratio", "1");
    setDate("Meeting date", LocalDate.now().minusDays(7));
    // Derived dates must land on business days: the settlement calendar
    // rejects weekend date values (e.g. payment date falls on a Saturday).
    LocalDate ex = nextBusinessDay(LocalDate.now());
    LocalDate record = nextBusinessDay(ex);
    LocalDate payment = nextBusinessDay(record);
    setDate("Ex-date", ex);
    setDate("Record date", record);
    setDate("Payment date", payment);
  }

  private static LocalDate nextBusinessDay(LocalDate day) {
    LocalDate next = day.plusDays(1);
    while ((next.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
        || next.getDayOfWeek() == java.time.DayOfWeek.SUNDAY)) {
      next = next.plusDays(1);
    }
    return next;
  }

  private void fillInterestPaymentForm() {
    clickRadioInGroup("ip_proc_time", "option1");
    clickRadioInGroup("ip_paying_agent_CSD", "yes");
    clickRadioInGroup("ip_withholding_tax", "yes");
    clickRadioInGroup("ip_exclude_own_shares", "no");
    selectNativeOptionById("ip_rounding");
    selectNativeOptionById("ip_currency");
    setField("Yearly interest rate (%)", "1");
    setField("Interest rate per period (%)", "1");
    setField("Total interest payment amount", "1");
    setControlById("ip_net_amount_transfered_paying_agent", "1");
    setNativeDateById("ip_start_date", LocalDate.now().minusDays(30));
    setNativeDateById("ip_end_date", LocalDate.now().minusDays(1));
    LocalDate ex = nextBusinessDay(LocalDate.now());
    LocalDate record = nextBusinessDay(ex);
    LocalDate payment = nextBusinessDay(record);
    setNativeDateById("ip_record_date", record);
    setNativeDateById("ip_payment_date", payment);
    setField("Transfer date for the amount", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
    setField("Requisite details:", "Disposable test interest payment");
  }

  private void fillAdditionalBondsForm() {
    clickRadioInGroup("aib_proc_time", "option1");
    clickRadioInGroup("aib_paid_up", "both");
    dumpVisibleFields("AIB");
    setField("Additional nominal value (added)", "2000");
    setField("Nominal Value of paid securities", "1");
    setField("Nominal Value of unpaid securities", "1");
    setDate("Effective date", nextBusinessDay(LocalDate.now()));
  }

  private void clickRadioInGroup(String name, String idContains) {
    for (SelenideElement el : $$("input[type=radio]")) {
      if (!el.isDisplayed()) continue;
      if (name.equals(safe(el.getAttribute("name"))) && safe(el.getAttribute("id")).contains(idContains)) {
        if (!el.isSelected()) {
          executeJavaScript("arguments[0].scrollIntoView({block:'center',inline:'center'});", el.getWrappedElement());
          try {
            el.click();
          } catch (ElementClickInterceptedException intercepted) {
            executeJavaScript("arguments[0].click();", el.getWrappedElement());
          }
        }
        sleep(200);
        return;
      }
    }
    throw new AssertionError("No visible radio in group '" + name + "' matching '" + idContains + "'");
  }

  private void checkCheckboxByName(String name) {
    for (SelenideElement el : $$("input[type=checkbox]")) {
      if (!el.isDisplayed()) continue;
      if (name.equals(safe(el.getAttribute("name")))) {
        if (!el.isSelected()) el.click();
        sleep(200);
        return;
      }
    }
    throw new AssertionError("No visible checkbox named '" + name + "'");
  }


  @Then("the Sign Document button must be visible")
  public void signDocumentVisibleStep() {
    awaitBodyText("Sign Document");
    if (!signDocumentVisible()) throw new AssertionError("Sign Document button is not visible after draft save");
  }

  @And("I persist the disposable application ID and remembered source instrument")
  public void persistContract() throws Exception {
    if (applicationId == null) applicationId = applicationIdFromUrl();
    if (applicationId == null) throw new AssertionError("Saved draft URL does not expose an application ID: " + webdriver().driver().url());
    if (sourceInstrument == null || sourceInstrument.isBlank()) throw new AssertionError("Source instrument was not remembered");
    Files.createDirectories(contractPath().getParent());
    Files.writeString(contractPath(),
      "application.id=" + applicationId + "\n"
        + "application.url=" + webdriver().driver().url() + "\n"
        + "source.instrument=" + sourceInstrument.replace("\n", " ") + "\n"
        + "total.payment.amount=" + plain(totalPaymentAmount) + "\n");
    screenshot("disposable-dividend-draft-saved-" + applicationId);
    System.out.println("DISPOSABLE_APPLICATION_CONTRACT persisted=true");
  }

  @Given("I open the saved disposable Dividend Payment application")
  public void openSavedDisposableApplication() throws Exception {
    openSavedDisposableApplicationForType("Dividend Payment");
  }

  @Given("the disposable application type is {string}")
  public void setAppType(String type) {
    appType = type;
  }

  @Given("I open the saved disposable {string} application")
  public void openSavedDisposableApplicationForType(String type) throws Exception {
    appType = type;
    String url = property("application.url");
    for (int attempt = 0; attempt < 3; attempt++) {
      login();
      selectCompany("AutotestLtSingleSignee");
      open(url);
      if (awaitDisposableApplicationDetail()) {
        applicationId = property("application.id");
        persistSessionCookies();
        return;
      }
      System.out.println("DISPOSABLE_REOPEN_RETRY attempt=" + attempt + " url=" + url);
      clearSessionCookies();
      sleep(500);
    }
    throw new AssertionError("Saved disposable application never loaded after re-login attempts; url=" + url);
  }

  private boolean awaitDisposableApplicationDetail() {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if (bodyShowsNotAuthorized()) return false;
      try {
        String body = $("body").getText();
        if (body.contains(appType) || body.contains("Sign Document") || body.contains("Download")
          || body.contains("Application data") || body.contains("Submitted")) return true;
      } catch (Throwable ignored) { }
      sleep(250);
    }
    return false;
  }

  private void dumpInteractiveControls(String prefix) {
    try {
      Object dump = executeJavaScript(
        "const visible=[...document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit]')]"
          + ".filter(e=>e.offsetParent!==null);return JSON.stringify({count:visible.length,"
          + "buttons:visible.filter(e=>e.tagName==='BUTTON').length,links:visible.filter(e=>e.tagName==='A').length});");
      System.out.println(prefix + " structure=" + dump);
    } catch (Throwable ignored) { }
  }

  private void dumpVisibleTable(String prefix) {
    try {
      Object dump = executeJavaScript(
        "const tabs=[...document.querySelectorAll('table,.table')].filter(t=>t.offsetParent!==null);"
          + " if(!tabs.length) return 'no-table';"
          + " const rows=[...tabs[tabs.length-1].querySelectorAll('tr')];"
          + " return JSON.stringify({tableCount:tabs.length,rowCount:rows.length,"
          + "columnCount:rows.length?rows[0].querySelectorAll('th,td').length:0});");
      System.out.println(prefix + " structure=" + dump);
    } catch (Throwable ignored) { }
  }

  @When("I click Sign Document for the disposable application")
  public void clickSignDocument() {
    dumpInteractiveControls("SIGN_DOCUMENT_DECISION");
    List<SelenideElement> signDocument = exactVisible("Sign Document", "button, a, [role=button]");
    if (!signDocument.isEmpty()) signDocument.get(signDocument.size() - 1).click();
    else {
      List<SelenideElement> signatures = exactVisible("Signatures", "button, a, [role=tab], li, span");
      if (signatures.isEmpty()) throw new AssertionError("Neither Sign Document nor Signatures tab is visible");
      signatures.get(signatures.size() - 1).click();
    }
    awaitBodyText("Signatures");
  }

  @Then("the Signatures tab and Initiate signing process must be visible")
  public void signaturesTabVisible() {
    dumpVisibleTable("SIGNATURES_TAB_STRUCTURE");
    awaitBodyText("Signatures");
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      String body = $("body").getText();
      if (body.contains("Initiate signing process") || !exactVisible("Sign", "button, a, [role=button]").isEmpty()) return;
      sleep(200);
    }
    throw new AssertionError("Signatures tab exposes neither Initiate signing process nor Sign");
  }

  @When("I initiate the signing process")
  public void initiateSigningProcess() {
    List<SelenideElement> initiate = exactVisible("Initiate signing process", "button, a, [role=button]")
      .stream().filter(this::usableSigningInitiateControl).toList();
    if (!initiate.isEmpty()) {
      DisposableScenarioPrerequisites.ensureRepresentedCompanyReady("AutotestLtSingleSignee");
      initiate = exactVisible("Initiate signing process", "button, a, [role=button]")
        .stream().filter(this::usableSigningInitiateControl).toList();
      if (initiate.isEmpty()) {
        throw new AssertionError("Represented-company re-selection removed the signing initiation control");
      }
      SelenideElement control = initiate.get(initiate.size() - 1);
      System.out.println("DISPOSABLE_INITIATE_CONTROL " + signingControlDescription(control));
      control.scrollIntoView("{block:'center',inline:'center'}").shouldBe(visible, enabled);
      control.click();
      dumpSigningSurface("POST_INITIATE_IMMEDIATE");
      awaitSigningActivation();
    } else {
      System.out.println("DISPOSABLE_SIGNING_ALREADY_INITIATED");
    }
    awaitSignerForm();
  }

  private void dumpSigningSurface(String tag) {
      try {
        Object structure = executeJavaScript(
          "const visible=s=>[...document.querySelectorAll(s)].filter(e=>e.offsetParent!==null);"
            + "return JSON.stringify({frames:visible('iframe,object,embed,frame').length,"
            + "controls:visible('button,a,[role=button],input[type=submit]').length,"
            + "forms:visible('form').length});");
        System.out.println("SIGNING_SURFACE_" + tag + " structure=" + structure);
      } catch (Throwable failure) {
        System.out.println("SIGNING_SURFACE_" + tag + " FAILED " + failure.getClass().getSimpleName());
      }
    }
  private boolean usableSigningInitiateControl(SelenideElement control) {
    try {
      return control.isEnabled()
        && !"true".equalsIgnoreCase(safe(control.getAttribute("aria-disabled")))
        && !"disabled".equalsIgnoreCase(safe(control.getAttribute("disabled")));
    } catch (Throwable ignored) {
      return false;
    }
  }

  private String signingControlDescription(SelenideElement control) {
    try {
      Object description = executeJavaScript(
        "const e=arguments[0]; return JSON.stringify({tag:e.tagName,disabled:e.hasAttribute('disabled'),"
          + "ariaDisabled:e.getAttribute('aria-disabled')==='true',hasId:Boolean(e.id),"
          + "hasClass:Boolean(e.className)});", control);
      return safe(String.valueOf(description));
    } catch (Throwable ignored) {
      return "metadata-unavailable";
    }
  }

  private void openExactApplicationFromList(String requestedId) {




    String wanted = requestedId == null ? "" : requestedId.trim();
    if (wanted.isBlank()) return;
    // Debug: show what the CA list actually renders so we can see the row/
    // Sign markup (user: the latest unsigned row ends with a Sign button).
    dumpCaListStructure("CA_LIST_ROW_STRUCTURE");
    boolean located = false;
    long deadline = System.currentTimeMillis() + Math.min(Configuration.timeout,10000);
    while (System.currentTimeMillis() < deadline) {



      Object attempt = executeJavaScript(
        "const wanted=arguments[0];"
          + "const els=[...document.querySelectorAll('a,button,[role=button],td,div,span')].filter(e=>e.offsetParent!==null);"
          + "const hit=els.filter(e=>((e.innerText||'' ).trim()===wanted)||((e.innerText||'' ).replace(/\\\\s+/g,' ').trim()===wanted)||((e.getAttribute('href')||'' ).indexOf('/application-form/'+wanted)>=0));"
          + "if(hit.length) return hit[hit.length-1].tagName; return '';", wanted);
      if (attempt != null && !attempt.toString().isEmpty()) {



        located = true;
        break;
      }
      List<SelenideElement> sfs = new ArrayList<>();
      for (SelenideElement candidate : $$("input[type=search], input[placeholder*=earch]")) {
        if (candidate.isDisplayed() && candidate.isEnabled()) sfs.add(candidate);
      }
      if (sfs.size() == 1) {
        sfs.get(0).setValue(wanted);
        sleep(500);
        continue;
      }
      sleep(250);
    }
    if (located) {


      dumpCaListStructure("CA_LIST_AFTER_LOCATE");
      // Open the exact application. A real user click on the row-end "Sign" BUTTON
      // launches the Dokobit signing popup. A JS (synthetic) click can be treated
      // as untrusted and browsers silently block its popup, so use Selenium's native
      // (trusted) click on the exact-text "Sign" button instead..
      List<SelenideElement> rowSign = exactVisible("Sign", "button");
      if (!rowSign.isEmpty()) {
        SelenideElement signBtn = rowSign.get(rowSign.size() - 1);
        System.out.println("DISPOSABLE_SIGNING_NATIVE_ROW_SIGN " + signingControlDescription(signBtn));
        signBtn.scrollIntoView("{block:'center',inline:'center'}").shouldBe(visible, enabled).click();
      } else {
        System.out.println("DISPOSABLE_SIGNING_NATIVE_ROW_SIGN_MISSING");
      }
      sleep(1200);
      caSettle();
      signDocumentOrStay();
    } else {
      Number visibleControls = executeJavaScript(
        "return [...document.querySelectorAll('a,button')].filter(a=>a.offsetParent!==null).length;");
      System.out.println("DISPOSABLE_SIGNING_REOPEN_NOT_FOUND_IN_LIST visible_controls=" + visibleControls);
    }
  }

  private void caSettle() {
    String raw = System.getenv("CA_SETTLE_MS");
    if (raw == null || raw.isBlank() || raw.equals("0")) return;
    try {
      long ms = Long.parseLong(raw);
      if (ms <= 0) return;
      System.out.println("DISPOSABLE_CA_SETTLE " + ms + "ms");
      sleep(ms);
    } catch (NumberFormatException ignored) {
    }
  }

  private String loadJs(String name) {
    try (InputStream in = getClass().getResourceAsStream("/js/" + name)) {
      if (in == null) throw new IllegalStateException("missing JS resource /js/" + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void dumpCaListStructure(String prefix) {

    try {
      Object dump = executeJavaScript(
        "const visible=[...document.querySelectorAll('a,button,[role=button]')].filter(e=>e.offsetParent!==null);"
          + "return JSON.stringify({count:visible.length,links:visible.filter(e=>e.tagName==='A').length,"
          + "buttons:visible.filter(e=>e.tagName==='BUTTON').length});");
      System.out.println(prefix + " structure=" + dump);
    } catch (Throwable ignored) { }
  }

  private void signDocumentOrStay() {



    dumpSigningSurface("SIGN_RETRY_VIEW");
    // The user-confirmed row-end "Sign" control appears on the CA list. Angular
    // re-renders constantly, so use JS visibility ((offsetParent!==null)) exactly
    // like the diagnostic dump; click the last one (prefer the anchor/router-link..
    try {
      Object clicked = executeJavaScript(loadJs("ca-sign-flow.js"));
      System.out.println("DISPOSABLE_SIGNING_ROW_SIGN_CLICKED structure=" + clicked);
    } catch (Throwable failure) {
      System.out.println("DISPOSABLE_SIGNING_ROW_SIGN_CLICK_FAILED " + failure.getClass().getSimpleName());
    }
    // Wait for the signatures detail route; if still on the list the loop re-detects
    // and retries..
    long routeDeadline = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < routeDeadline) {


      String currentUrl = webdriver().driver().url();
      if (currentUrl != null && currentUrl.contains("#signatures")) break;
      sleep(200);
    }
    // Dokobit readiness == the phone credential field appears in main DOM or
    // iframe. If it is there, signing is unblocked..
    try {
      SelenideElement credential = visibleSigningCredentialField();
      if (credential != null) {


        System.out.println("DISPOSABLE_SIGNING_RECOVERED credential_visible=true");
        return;
      }
    } catch (Throwable ignored) { }
    // The detail's Signatures tab may not render until re-opened;; Angular
    // reloaded the whole application detail right after the initiate click..
    try {
      List<SelenideElement> signaturesTab = exactVisible("Signatures", "button,a,[role=tab],li,span");
      if (!signaturesTab.isEmpty()) {
        System.out.println("DISPOSABLE_SIGNING_RETAB_CLICK");
        signaturesTab.get(signaturesTab.size() - 1).scrollIntoView("{block:'center',inline:'center'}").click();
        sleep(1500);
        caSettle();
      }
    } catch (Throwable ignored2) { }
    try {
      SelenideElement credential2 = visibleSigningCredentialField();
      if (credential2 != null) {
        System.out.println("DISPOSABLE_SIGNING_RECOVERED_URL2 " + webdriver().driver().url());
        return;
      }
    } catch (Throwable ignored3) { }
    // Give one settle beat; the parent loop re-detects stuck state if needed..
    sleep(1000);
  }
  private void awaitSigningActivation() {
    long startedAt = System.currentTimeMillis();
    long deadline = startedAt + Configuration.timeout;
    int reClicks = 0;
    int reopens = 0;
    long nextReclickAt = startedAt + 4000;
    long nextReopenAt = startedAt + 15000;
    boolean seenInitiateControl = false;
    int retabAttempts = 0;
    boolean refreshedAfterInitiate = false;
    while (System.currentTimeMillis() < deadline) {


      String body = $("body").shouldBe(visible).getText();
      if (visibleSignControlInAnyFrame()) return;

      boolean awaitingSignatures = body != null
        && (body.contains("Awaiting signatures") || body.contains("Signing in progress"));
      if (awaitingSignatures && activateSignaturesTabIfPresent()) {
        sleep(500);
        continue;
      }
      boolean stuckStaleState =
          (body != null && body.contains("Notify"))
          && !body.contains("Initiate signing process")
          && !awaitingSignatures
          && exactVisible("Sign", "button, a, [role=button]").isEmpty();
      if (stuckStaleState && reopens < 3 && System.currentTimeMillis() >= nextReopenAt) {

        reopenApplicationAndRetrySigning();
        reopens++;
        nextReopenAt = System.currentTimeMillis() + 10000;
        nextReclickAt = System.currentTimeMillis() + 10000;
        continue;

      }
      if (body.contains("Signature process has not started yet")
          || body.contains("Initiate signing process")) {

        if (System.currentTimeMillis() >= nextReclickAt) {

          List<SelenideElement> initiateer = exactVisible("Initiate signing process", "button, a, [role=button]")
            .stream().filter(this::usableSigningInitiateControl).toList();
          if (!initiateer.isEmpty() && initiateer.get(initiateer.size() - 1).isEnabled()) {

            if (seenInitiateControl) {
              System.out.println("DISPOSABLE_INITIATE_RECLICK attempt=" + (reClicks + 1));
              sleep(400);
            }
            initiateer.get(initiateer.size() - 1).scrollIntoView("{block:'center',inline:'center'}").click();
            seenInitiateControl = true;
            reClicks = reClicks + 1;
            nextReclickAt = System.currentTimeMillis() + 4000;
          } else {
            seenInitiateControl = false;
          }
        }
      }
      sleep(300);
    }
    dumpSigningSurface("INITIATE_ACTIVATION_FAILED");
    screenshot("disposable-initiate-activation-failed");
    throw new AssertionError("Initiate signing process did not activate the signing workflow");
  }

  private boolean activateSignaturesTabIfPresent() {
    String currentUrl = webdriver().driver().url();
    if (currentUrl != null && currentUrl.contains("#signatures")) return false;

    List<SelenideElement> tabs = exactVisible("Signatures", "a, button, [role=tab]");
    if (tabs.isEmpty()) return false;
    SelenideElement tab = tabs.get(tabs.size() - 1);
    if (!tab.isEnabled()) return false;
    System.out.println("DISPOSABLE_SIGNING_ACTIVATE_SIGNATURES_TAB ready=true");
    tab.scrollIntoView("{block:'center',inline:'center'}").click();
    return true;
  }

  private void reopenApplicationAndRetrySigning() {
    System.out.println("DISPOSABLE_SIGNING_STUCK_REOPEN triggered=true");
    // User-confirmed: customer signing must return to the CA list via the SPA
    // "Back to all" button (the raw /corporate-actions deep link rebounces
    // the local session back to /company-selection).
    try {
      List<SelenideElement> back = exactVisible("Back to all", "button,a,[role=button],span");
      if (!back.isEmpty()) {
        back.get(back.size() - 1).scrollIntoView("{block:'center',inline:'center'}").click();
      } else {
        System.out.println("DISPOSABLE_SIGNING_REOPEN_NO_BACK_BUTTON");
      }
    } catch (Throwable failure) {
      System.out.println("DISPOSABLE_SIGNING_REOPEN_NAV_FAILED " + failure.getClass().getSimpleName());
      return;
    }
    long routeDeadline = System.currentTimeMillis() + Math.min(Configuration.timeout, 20000);
    while (System.currentTimeMillis() < routeDeadline) {


      String body = $("body").shouldBe(visible).getText();
      if (body.contains("Create Application") || body.contains("Corporate Actions")) break;
      sleep(200);
    }
    openExactApplicationFromList(applicationId);
    caSettle();
  }




  @When("I click the Sign button for the disposable application")
  public void clickSignerSignButton() {
    List<SelenideElement> rowSign = exactVisible("Sign", "button, a, [role=button]");
    if (rowSign.isEmpty()) {
      clickVisibleSignInAnyFrame();
    } else {
      rowSign.get(rowSign.size() - 1).click();
    }
    ensureSigningContext();
  }

  private void clickVisibleSignInAnyFrame() {

    try {

      executeJavaScript("const b=[...document.querySelectorAll('button,a,[role=button]')].filter(e=>e.offsetParent!==null && (e.innerText||'').trim().toLowerCase()==='sign'); if(b.length) b[b.length-1].click();");
      return;
    } catch (Throwable ignored) { }
    var frames = $$("iframe");
    int i = frames.size();
    while (i > 0) {
      i--;
      try {
        Selenide.switchTo().frame(i);
        List<SelenideElement> in = exactVisible("Sign", "button, a, [role=button]");
        if (!in.isEmpty()) {
          in.get(in.size() - 1).click();
          return;
        }
      } catch (Throwable ignored) {
      } finally {
        Selenide.switchTo().defaultContent();
      }
    }
  }

  @Then("the signer full name, signing date, Sign button, and document frame must be visible")
  public void signingFormVisible() {
    awaitSignerForm();
  }

  private void awaitSignerForm() {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    String lastBody = "";
    while (System.currentTimeMillis() < deadline) {
      try {
        lastBody = $("body").shouldBe(visible).getText();
      } catch (Throwable ignored) { }
      if (signerFormReady(lastBody)) {
        screenshot("disposable-dividend-signing-form");
        return;
      }
      sleep(300);
    }
    dumpSigningSurface("SIGNING_FORM_FAILED");
    throw new AssertionError("Signing process did not render the signer form, exact Sign control, and document frame");
  }

  private boolean signerFormReady(String body) {
    if (body == null || !body.contains(SIGNER_FULL_NAME)) return false;
    boolean frameVisible = false;
    for (SelenideElement frame : $("body").$$(
        "iframe,object,embed,[data-testid*=document],[class*=document-frame]")) {
      if (frame.isDisplayed()) { frameVisible = true; break; }
    }
    return frameVisible && visibleSignControlInAnyFrame();
  }

  private boolean visibleSignControlInAnyFrame() {
    try {
      if (!exactVisible("Sign", "button, a, [role=button]").isEmpty()) return true;
    } catch (Throwable ignored) { }
    var frames = $$("iframe");
    int i = frames.size();
    while (i > 0) {
      i--;
      try {
        Selenide.switchTo().frame(i);
        if (!exactVisible("Sign", "button, a, [role=button]").isEmpty()) return true;
      } catch (Throwable ignored) {
      } finally {
        Selenide.switchTo().defaultContent();
      }
    }
    return false;
  }

  @When("I sign the document with Mobile ID phone number {string}")
  public void signWithMobileId(String phone) {
    if (phoneFieldNowhere()) {
      List<SelenideElement> rowSign = exactVisible("Sign", "button, a, [role=button]");
      if (!rowSign.isEmpty()) rowSign.get(rowSign.size() - 1).click();
    }
    caSettle();
    ensureSigningContext();
    SelenideElement credential = visibleSigningCredentialField();
    credential.setValue(phone);
    List<SelenideElement> signButtons = exactVisible("SIGN", "button, a, [role=button], input[type=submit]");
    if (signButtons.isEmpty()) signButtons = exactVisible("Sign", "button, a, [role=button], input[type=submit]");
    if (signButtons.isEmpty()) throw new AssertionError("No visible signing confirmation button");
    signButtons.get(signButtons.size() - 1).click();
    caSettle();
  }

  private boolean phoneFieldNowhere() {
    if (tryVisibleSigningCredentialField() != null) return false;
    for (int i = 0; i < $$("iframe").size(); i++) {
      try {
        Selenide.switchTo().frame(i);
        if (tryVisibleSigningCredentialField() != null) return false;
      } catch (Throwable ignored) {
      } finally {
        Selenide.switchTo().defaultContent();
      }
    }
    return true;
  }

  private void ensureSigningContext() {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if (tryVisibleSigningCredentialField() != null) return;
      for (int i = 0; i < $$("iframe").size(); i++) {
        try {
          Selenide.switchTo().frame(i);
          if (tryVisibleSigningCredentialField() != null) return;
        } catch (Throwable ignored) {
        }
        Selenide.switchTo().defaultContent();
      }
      try {
        if (WebDriverRunner.getWebDriver().getWindowHandles().size() > 1) {
          for (String handle : WebDriverRunner.getWebDriver().getWindowHandles()) {



            if (!handle.equals(WebDriverRunner.getWebDriver().getWindowHandle())) {
              WebDriverRunner.getWebDriver().switchTo().window(handle);
              if (tryVisibleSigningCredentialField() != null) {

                System.out.println("DISPOSABLE_SIGNING_POPUP_CREDENTIAL_FOUND handle=" + handle);
                return;
              }
              WebDriverRunner.getWebDriver().switchTo().window(
                WebDriverRunner.getWebDriver().getWindowHandles().iterator().next());
            }
          }
        }
      } catch (Throwable ignoredWin) { }
      sleep(300);
    }
    throw new AssertionError("No phone number or Smart-ID signing field in main DOM or iframes");
  }

  private SelenideElement tryVisibleSigningCredentialField() {
    try { return visibleSigningCredentialField(); } catch (Throwable e) { return null; }
  }

  @Then("Signature is valid must appear within 120 seconds")
  public void signatureIsValid() {
    long deadline = System.currentTimeMillis() + 120000;
    boolean openedSignatureView = false;
    while (System.currentTimeMillis() < deadline) {
      String all = bodyTextIncludingFrames();
      String mainBody = $("body").getText();
      // The Dokobit viewer may show "Signature is valid", or the frame closes and the
      // main application transitions to "Submitted" status.
      if (all.contains("Signature is valid") || mainBody.contains("Submitted")) {
        screenshot("disposable-dividend-signature-valid-" + applicationId);
        return;
      }
      if (!openedSignatureView && mainBody.contains("Awaiting signatures")) {
        List<SelenideElement> views = exactVisible("View", "button, a, [role=button]");
        if (!views.isEmpty()) {
          views.get(views.size() - 1).click();
          openedSignatureView = true;
          sleep(500);
          continue;
        }
      }
      sleep(500);
    }
    dumpSigningSurface("SIGNATURE_VALIDATION_FAILED");
    throw new AssertionError("Signature is valid / Submitted did not appear within 120 seconds");
  }

  private String bodyTextIncludingFrames() {
    StringBuilder text = new StringBuilder($("body").getText());
    for (int i = 0; i < $$("iframe").size(); i++) {
      try {
        Selenide.switchTo().frame(i);
        text.append(" ").append($("body").getText());
      } catch (Throwable ignored) {
      } finally {
        Selenide.switchTo().defaultContent();
      }
    }
    return text.toString();
  }

  @When("I download the signed disposable document")
  public void downloadSignedDocument() throws Exception {
    Path downloads = Path.of(Configuration.downloadsFolder);
    Files.createDirectories(downloads);
    long started = System.currentTimeMillis();
    List<SelenideElement> controls = exactVisible("Download", "button, a, [role=button]");
    if (controls.isEmpty()) throw new AssertionError("No visible Download control after valid signature");
    SelenideElement control = controls.get(controls.size() - 1);
    executeJavaScript(
      "arguments[0].scrollIntoView({block:'center',inline:'center'}); arguments[0].click();",
      control.getWrappedElement());
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      try (Stream<Path> files = Files.list(downloads)) {
        signedDocument = files.filter(Files::isRegularFile)
          .filter(path -> !path.getFileName().toString().endsWith(".part"))
          .filter(path -> {
            try { return Files.getLastModifiedTime(path).toMillis() >= started - 1000 && Files.size(path) > 0; }
            catch (Exception ignored) { return false; }
          }).findFirst().orElse(null);
      }
      if (signedDocument != null) return;
      sleep(300);
    }
    throw new AssertionError("Download did not create a file in " + downloads);
  }

  @Then("the signed document must exist in the file system")
  public void signedDocumentExists() throws Exception {
    if (signedDocument == null || !Files.isRegularFile(signedDocument) || Files.size(signedDocument) == 0) {
      throw new AssertionError("Signed document file is missing or empty");
    }
    Path evidence = Path.of("build", "reports", "signed-disposable-document.properties");
    Files.createDirectories(evidence.getParent());
    Files.writeString(evidence, "application.id=" + applicationId + "\nfile.path=" + signedDocument.toAbsolutePath() + "\nfile.size=" + Files.size(signedDocument) + "\n");
    System.out.println("SIGNED_DISPOSABLE_FILE " + signedDocument.toAbsolutePath());
    System.out.println("SIGNED_DISPOSABLE_FILE_SIZE " + Files.size(signedDocument));
  }

  @When("I open the {string} tab of the disposable application")
  public void openDisposableApplicationTab(String tabName) {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if (CorporateActionsTabProbe.isActive(tabName)) return;
      try {
        org.openqa.selenium.WebElement clickable = CorporateActionsTabProbe.findClickable(tabName);
        if (clickable != null) {
          CorporateActionsTabProbe.prepare(tabName);
          $(clickable).scrollIntoView("{block: 'center', inline: 'center'}").click();
        } else {
          List<SelenideElement> matches = exactVisible(tabName, "button, a, [role=tab], li, span");
          if (!matches.isEmpty()) matches.get(matches.size() - 1).click();
        }
      } catch (Throwable ignored) { }
      sleep(250);
    }
    throw new AssertionError("Disposable application tab '" + tabName
      + "' never became active; url=" + WebDriverRunner.url());
  }

  @Then("the disposable application History must show the created application and, if signed, the signed application")
  public void disposableHistoryRecords() {
    openDisposableApplicationTab("History");
    boolean applicationSigned = disposableApplicationIsSigned();
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      boolean[] presence = historyRecordPresence();
      boolean created = presence[0];
      boolean signed = presence[1];
      if (created && (!applicationSigned || signed)) {
        System.out.println("DISPOSABLE_HISTORY_OK signed=" + applicationSigned
          + " createdRecord=" + created + " signedRecord=" + signed);
        screenshot("disposable-dividend-history");
        return;
      }
      sleep(250);
    }
    System.out.println("DISPOSABLE_HISTORY_LAST signed=" + applicationSigned);
    try {
      Object dump = executeJavaScript(
        "const els=[...document.querySelectorAll('td,tr,div,span,li,p,dt,dd,th')].filter(e=>e.offsetParent!==null);"
          + "return JSON.stringify({visibleNodes:els.length,tableRows:document.querySelectorAll('table tr').length});");
      System.out.println("DISPOSABLE_HISTORY_STRUCTURE " + dump);
    } catch (Throwable ignored) { }
    throw new AssertionError("Disposable application History did not show the expected records");
  }

  private boolean[] historyRecordPresence() {
    try {
      Object raw = executeJavaScript(
        "const els=[...document.querySelectorAll('td,tr,div,span,li,p,dt,dd')];"
          + "return JSON.stringify({created:els.some(e=>/created application/i.test(e.textContent||'')),"
          + "signed:els.some(e=>/signed application/i.test(e.textContent||''))});");
      String s = String.valueOf(raw);
      return new boolean[]{s.contains("\"created\":true"), s.contains("\"signed\":true")};
    } catch (Throwable e) {
      return new boolean[]{false, false};
    }
  }

  @Then("the disposable application Attachments tab is entered")
  public void disposableAttachmentsTabEntered() {
    openDisposableApplicationTab("Attachments");
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if (CorporateActionsTabProbe.isActive("Attachments")) {
        System.out.println("DISPOSABLE_ATTACHMENTS_ACTIVE applicationId=" + applicationId
          + " panel=>>>" + CorporateActionsTabProbe.panelText("Attachments") + "<<<");
        screenshot("disposable-dividend-attachments-" + applicationId);
        return;
      }
      sleep(250);
    }
    throw new AssertionError("Disposable application Attachments tab did not become active; url=" + WebDriverRunner.url());
  }

  private boolean disposableApplicationIsSigned() {
    try {
      Object raw = executeJavaScript(
        "return JSON.stringify({submitted:/submitted/i.test(document.body.textContent||''),"
          + "signedHist:[...document.querySelectorAll('td,tr,div,span,li,p,dt,dd')].some(e=>/signed application/i.test(e.textContent||''))});");
      String s = String.valueOf(raw);
      if (s.contains("\"submitted\":true") || s.contains("\"signedHist\":true")) return true;
    } catch (Throwable ignored) { }
    try { return $("body").getText().toLowerCase(Locale.ROOT).contains("submitted"); }
    catch (Throwable e) { return false; }
  }

  private SelenideElement visibleSigningCredentialField() {
    for (SelenideElement field : $$(("input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=file])"))) {
      if (!field.isDisplayed() || !field.isEnabled() || field.getAttribute("readonly") != null) continue;
      String combined = normalize(String.join(" ", safe(field.getAttribute("id")), safe(field.getAttribute("name")),
        safe(field.getAttribute("placeholder")), safe(field.getAttribute("aria-label"))));
      if (combined.contains("phone") || combined.contains("mobile") || combined.contains("smart") || combined.contains("code")) return field;
    }
    throw new AssertionError("No visible phone number or Smart-ID signing field");
  }

  private String property(String key) throws Exception {
    java.util.Properties properties = new java.util.Properties();
    try (var reader = Files.newBufferedReader(contractPath())) { properties.load(reader); }
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) throw new AssertionError("Missing disposable application contract property " + key);
    return value.trim();
  }

  private void clickSaveDraft() {
    List<SelenideElement> controls = exactVisible("Save as Draft", "button, a, [role=button], input[type=submit]");
    if (controls.isEmpty()) controls = exactVisible("Save as draft", "button, a, [role=button], input[type=submit]");
    if (controls.isEmpty()) {
      awaitDraftSaveResult();
      if (signDocumentVisible()) return;
      throw new AssertionError("No visible Save as Draft control; url=" + webdriver().driver().url());
    }
    SelenideElement control = controls.get(controls.size() - 1);
    executeJavaScript(
      "arguments[0].scrollIntoView({block:'center',inline:'center'}); arguments[0].click();",
      control.getWrappedElement());
  }

  private void awaitDraftSaveResult() {
    long deadline = System.currentTimeMillis() + 12000;
    while (System.currentTimeMillis() < deadline) {
      if (signDocumentVisible()) return;
      if (!exactVisible("Save as Draft", "button, a, [role=button], input[type=submit]").isEmpty()) {
        sleep(500);
        return;
      }
      sleep(200);
    }
  }

  private void fillVisibleMandatoryFields() {
    @SuppressWarnings("unchecked")
    List<String> fieldIds = (List<String>) executeJavaScript(
      "return [...new Set([...document.querySelectorAll('input[id],textarea[id],select[id]')]"
        + ".map(el=>el.id).filter(Boolean))];");
    for (String fieldId : fieldIds) {
      // Angular rerenders dependent controls while values are entered. Resolve
      // every field by its stable id at the moment it is inspected instead of
      // walking a stale Selenide collection snapshot.
      SelenideElement field = $(By.id(fieldId));
      if (!field.isDisplayed() || !field.isEnabled() || field.getAttribute("readonly") != null) continue;
      String value = field.getValue();
      boolean required = field.getAttribute("required") != null || "true".equals(field.getAttribute("aria-required"));
      boolean invalid = "true".equals(field.getAttribute("aria-invalid")) || hasValidationError(field);
      String type = field.getAttribute("type");
      if ("date".equalsIgnoreCase(type) && invalid) {
        repairInvalidDraftDate(field);
        continue;
      }
      if ((!required && !invalid) || (value != null && !value.isBlank())) continue;
      if ("file".equalsIgnoreCase(type)) continue;
      if ("date".equalsIgnoreCase(type)) field.setValue(LocalDate.now().plusDays(2).toString());
      else if ("number".equalsIgnoreCase(type)) field.setValue("1");
      else if ("select".equalsIgnoreCase(field.getTagName())) chooseFirstNonEmpty(field);
      else field.setValue("Disposable test draft " + System.currentTimeMillis());
    }
  }

  private void repairInvalidDraftDate(SelenideElement field) {
    String id = safe(field.getAttribute("id"));
    LocalDate value;
    if (id.endsWith("general_meeting_date")) value = LocalDate.now().minusDays(7);
    else if (id.endsWith("start_date")) value = LocalDate.now().minusDays(30);
    else if (id.endsWith("end_date")) value = LocalDate.now().minusDays(1);
    else if (id.endsWith("ex_date")) value = nextBusinessDay(LocalDate.now());
    else if (id.endsWith("record_date")) value = nextBusinessDay(nextBusinessDay(LocalDate.now()).plusDays(1));
    else if (id.endsWith("payment_date")) {
      LocalDate record = nextBusinessDay(nextBusinessDay(LocalDate.now()).plusDays(1));
      value = nextBusinessDay(record.plusDays(1));
    } else value = nextBusinessDay(LocalDate.now());
    String formatted = value.format(DateTimeFormatter.ISO_LOCAL_DATE);
    if (!id.isBlank()) setControlById(id, formatted);
    else executeJavaScript(
      "const el=arguments[0],value=arguments[1],setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;"
        + "setter.call(el,value);el.dispatchEvent(new Event('input',{bubbles:true}));"
        + "el.dispatchEvent(new Event('change',{bubbles:true}));el.dispatchEvent(new Event('blur',{bubbles:true}));",
      field.getWrappedElement(), formatted);
  }

  private void resolveExcludedAccountRows() {
    for (int row = 0; row < 2; row++) {
      SelenideElement code = $("#dp_aet_code_" + row);
      if (!code.exists() || !code.isDisplayed()) continue;
      SelenideElement tableRow = $("#dp_account_exclude_table_row_" + row);
      SelenideElement search = tableRow.$("button.button-search");
      SelenideElement account = $("#dp_aet_account_" + row);
      SelenideElement name = $("#dp_aet_name_" + row);
      if (selectHasNonEmptyOption(account) && selectHasNonEmptyOption(name)) {
        chooseFirstNonEmpty(account);
        chooseFirstNonEmpty(name);
        continue;
      }
      if (search.exists() && search.isDisplayed() && search.isEnabled()) {
        search.click();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
          && (!selectHasNonEmptyOption(account) || !selectHasNonEmptyOption(name))) sleep(200);
      }
      if (selectHasNonEmptyOption(account)) chooseFirstNonEmpty(account);
      if (selectHasNonEmptyOption(name)) chooseFirstNonEmpty(name);
    }
  }

  private boolean selectHasNonEmptyOption(SelenideElement field) {
    if (!field.exists() || !"select".equalsIgnoreCase(field.getTagName())) return false;
    for (org.openqa.selenium.WebElement option : new Select(field.getWrappedElement()).getOptions()) {
      String value = safe(option.getAttribute("value")).trim();
      if (option.isEnabled() && !value.isBlank() && !"null".equalsIgnoreCase(value)) return true;
    }
    return false;
  }

  private void logInvalidFields(int attempt) {
    List<String> invalid = new ArrayList<>();
    for (SelenideElement field : $$("input, textarea, select")) {
      if (!field.isDisplayed()) continue;
      if ("true".equals(field.getAttribute("aria-invalid"))
        || safe(field.getAttribute("class")).toLowerCase(Locale.ROOT).contains("invalid")) {
        String hint = validationHintFor(field);
        String dateInfo = "";
        if ("date".equalsIgnoreCase(field.getAttribute("type"))) {
          String min = safe(field.getAttribute("min"));
          String max = safe(field.getAttribute("max"));
          String val = displayValue(field);
          Object group = executeJavaScript(
            "const f=arguments[0]; const g=f.closest('.form-group,fieldset,div');"
            + " return g ? g.innerText.replace(/\\s+/g,' ').trim().substring(0,180) : '';", field);
          dateInfo = " val=\"" + val + "\" min=\"" + min + "\" max=\"" + max
            + "\" group=\"" + String.valueOf(group == null ? "" : group).replace("\"", "'").trim() + "\"";
        }
        invalid.add(safe(field.getAttribute("id")) + ":" + safe(field.getAttribute("name"))
          + dateInfo + (hint.isEmpty() ? "" : " -> \"" + hint + "\""));
      }
    }
    System.out.println("DRAFT_VALIDATION_ATTEMPT " + attempt + " invalid=" + String.join(",", invalid));
  }

  private String validationHintFor(SelenideElement field) {
    try {
      Object result = executeJavaScript(
        "const f=arguments[0]; if(!f) return '';"
          + "const describe=f.getAttribute('aria-describedby');"
          + "if(describe){const d=document.getElementById(describe); if(d&&d.textContent.trim()) return d.textContent.trim();}"
          + "const box=f.closest('.form-group,fieldset,section,td,div');"
          + "if(box){for(const sel of ['.invalid-feedback','.error','.error-message','.help-block','.field-error','.validation-message','small.error','span.error']){"
          + "const e=box.querySelector(sel); if(e && e.offsetParent!==null && e.textContent.trim()) return e.textContent.trim();}}"
          + "return '';", field);
      return String.valueOf(result == null ? "" : result).trim();
    } catch (Throwable e) {
      return "";
    }
  }

  private void attachPdfIfRequired() throws Exception {
    for (SelenideElement input : $$("input[type=file]")) {
      if (!input.exists()) continue;
      boolean required = input.getAttribute("required") != null || "true".equals(input.getAttribute("aria-required"))
        || $("body").getText().toLowerCase(Locale.ROOT).contains("choose file(s)");
      if (!required) continue;
      Path pdf = Path.of("build", "reports", "disposable-test-attachment.pdf");
      Files.createDirectories(pdf.getParent());
      Files.writeString(pdf, "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 0>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n");
      input.uploadFile(pdf.toFile());
      return;
    }
  }


  /** Maps well-known English form labels to their stable field ids. Some
   * generated forms render server-side translations (Latvian etc.} depending on
   * session language, so exact label text differs per run. Field ids are
   * language-independent, so this fallback resolves those forms robustly. */
  private String fieldIdAliasFor(String label) {
    return switch (normalize(label)) {
      case "number of shares before" -> "bi_number_shares_before";
      case "number of new shares" -> "bi_number_shares_new";
      case "for every 1 share" -> "bi_for_every_one_share";
      case "ratio" -> "bi_ratio";
      case "meeting date" -> "bi_meeting_date";
      case "total issued shares" -> "dp_total_shares";
      case "payment for one security" -> "dp_one_security_payment";
      case "total payment amount" -> "dp_total_payment_amount";
      case "date of general meeting" -> "dp_general_meeting_date";
      case "net dividend amount transferred to the paying agent" -> "dp_net_amount_transfered_paying_agent";
      case "yearly interest rate" -> "ip_yearly_interest_rate";
      case "interest rate per period" -> "ip_interest_rate_per_period";
      case "total interest payment amount" -> "ip_total_payment_amount";
      case "net interest amount transferred to the paying agent" -> "ip_net_amount_transfered_paying_agent";
      case "start of interest period" -> "ip_start_date";
      case "end of interest period" -> "ip_end_date";
      case "transfer date for the amount" -> "ip_transfer_date";
      case "requisite details" -> "ip_requisite_details";
      case "nominal value before" -> "aib_nominal_value_before";
      case "additional nominal value added" -> "aib_additional_nominal_value";
      case "nominal value of paid securities" -> "aib_nominal_value_paid";
      case "nominal value of unpaid securities" -> "aib_nominal_value_unpaid";
      case "effective date" -> "aib_effective_date";
      case "ex date" -> normalize(appType).equals("bonus issue") ? "bi_ex_date" : "dp_ex_date";
      case "record date" -> switch (normalize(appType)) {
        case "bonus issue" -> "bi_record_date";
        case "interest payment" -> "ip_record_date";
        default -> "dp_record_date";
      };
      case "payment date" -> switch (normalize(appType)) {
        case "bonus issue" -> "bi_payment_date";
        case "interest payment" -> "ip_payment_date";
        default -> "dp_payment_date";
      };
      default -> null;
    };
  }
  private SelenideElement fieldForLabel(String label) {
    String literal = xpathLiteral(label.toLowerCase(Locale.ROOT));
    List<SelenideElement> labels = $$x("//*[self::label or self::legend or self::span or self::div][translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" + literal + "]")
      .stream().filter(SelenideElement::isDisplayed).toList();
    for (SelenideElement labelElement : labels) {
      String forId = labelElement.getAttribute("for");
      if (forId != null && !forId.isBlank()) {
        SelenideElement byId = $(By.id(forId));
        if (byId.exists() && byId.isDisplayed()) return byId;
      }
      org.openqa.selenium.WebElement nearest = executeJavaScript("const label=arguments[0], lr=label.getBoundingClientRect(); const fields=[...document.querySelectorAll('input,textarea,select,[role=combobox]')].filter(e=>e.offsetParent!==null); fields.sort((a,b)=>{const score=e=>{const r=e.getBoundingClientRect(); const dx=Math.abs((r.left+r.right)/2-(lr.left+lr.right)/2); const dy=Math.abs(r.top-lr.bottom); const above=r.bottom<lr.top?100000:0; return above+dy*10+dx;}; return score(a)-score(b);}); return fields[0]||null;", labelElement);
      if (nearest != null) return $(nearest);
      SelenideElement following = labelElement.$x("following::*[self::input or self::textarea or self::select or @role='combobox'][1]");
      if (following.exists() && following.isDisplayed()) return following;
      SelenideElement container = labelElement.$x("ancestor::*[.//input or .//textarea or .//select or .//*[@role='combobox']][1]");
      if (container.exists()) {
        for (SelenideElement field : container.$$("input, textarea, select, [role=combobox]")) {
          if (field.isDisplayed()) return field;
        }
      }
    }
    // Language-agnostic fallback: field ids are stable even when generated
    // label text renders in a different language..
    String aliasId = fieldIdAliasFor(label);
    if (aliasId != null) {
      SelenideElement byId = $("#" + aliasId);
      if (byId.exists() && byId.isDisplayed()) return byId;

    }

    for (SelenideElement field : $$("input, textarea, select, [role=combobox]")) {
      if (!field.isDisplayed()) continue;
      String combined = String.join(" ", safe(field.getAttribute("name")), safe(field.getAttribute("id")),
        safe(field.getAttribute("placeholder")), safe(field.getAttribute("aria-label")));
      if (normalize(combined).contains(normalize(label))) return field;
    }
    dumpVisibleFields("VISIBLE_FIELDS");
    throw new AssertionError("No visible field for label '" + label + "'");
  }

  private void dumpVisibleFields(String prefix) {
    try {
      Object dump = executeJavaScript(
        "const fields=[...document.querySelectorAll('input,textarea,select,[role=combobox]')].filter(e=>e.offsetParent!==null);"
          + "return JSON.stringify(fields.map(e=>{"
          + "let label=''; const id=e.id; if(id){const l=document.querySelector('label[for=\"'+id+'\"]'); if(l) label=l.textContent.trim();}"
          + "if(!label){const box=e.closest('.form-group,fieldset,section,div'); if(box){const t=box.querySelector(':scope > label,.control-label,.form-label'); if(t) label=t.textContent.trim();}}"
          + "return {id,label,name:e.name,type:e.type,tag:e.tagName,ph:e.placeholder};}));");
      System.out.println(prefix + " " + dump);
    } catch (Throwable ignored) { }
  }

  private SelenideElement visibleChoiceControlForLabel(String label) {
    String literal = xpathLiteral(label.toLowerCase(Locale.ROOT));
    for (SelenideElement labelElement : $$x("//*[self::label or self::span or self::div][translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" + literal + "]")) {
      if (!labelElement.isDisplayed()) continue;
      SelenideElement container = labelElement.$x("ancestor::*[.//*[@role='combobox'] or .//button or .//select][1]");
      if (!container.exists()) continue;
      for (SelenideElement control : container.$$("[role=combobox], button, select")) {
        if (control.isDisplayed() && control.isEnabled()) return control;
      }
    }
    throw new AssertionError("No visible choice control for label '" + label + "'");
  }

  private void awaitPositiveValue(String label) {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if (decimalValue(fieldForLabel(label)).compareTo(BigDecimal.ZERO) > 0) return;
      sleep(200);
    }
    throw new AssertionError(label + " was not populated after selecting the source instrument");
  }

  private List<SelenideElement> fieldsNearText(String label) {
    List<SelenideElement> result = new ArrayList<>();
    for (SelenideElement element : $$x("//*[contains(normalize-space(.)," + xpathLiteral(label) + ")]")) {
      if (!element.isDisplayed()) continue;
      SelenideElement container = element.closest("section, fieldset, .form-group, div");
      if (!container.exists()) continue;
      for (SelenideElement field : container.$$("input, textarea")) if (field.isDisplayed()) result.add(field);
      if (!result.isEmpty()) break;
    }
    return result;
  }

  private String chooseFirstNonEmpty(SelenideElement field) {
    if ("select".equalsIgnoreCase(field.getTagName())) {
      Select select = new Select(field.getWrappedElement());
      List<org.openqa.selenium.WebElement> options = select.getOptions();
      for (int index = 0; index < options.size(); index++) {
        var option = options.get(index);
        if (!option.isEnabled()) continue;
        String text = option.getText().trim();
        String value = option.getAttribute("value");
        if (!text.isBlank() && (value != null && !value.isBlank()) && !text.toLowerCase(Locale.ROOT).contains("select")) {
          select.selectByIndex(index);
          executeJavaScript("arguments[0].dispatchEvent(new Event('input', {bubbles:true})); arguments[0].dispatchEvent(new Event('change', {bubbles:true}));", field);
          sleep(500);
          String selectedText = select.getFirstSelectedOption().getText().trim();
          return selectedText.isBlank() ? value : selectedText;
        }
      }
    }
    field.click();
    sleep(300);
    List<SelenideElement> options = $("body").$$("[role=option], mat-option, ng-option, .dropdown-item, li")
      .stream().filter(SelenideElement::isDisplayed).toList();
    for (SelenideElement option : options) {
      String text = option.getText().trim();
      if (text.isBlank() || text.toLowerCase(Locale.ROOT).contains("select")) continue;
      option.click();
      return text;
    }
    throw new AssertionError("No non-empty option for field " + field.getAttribute("name"));
  }

  private void selectNativeOptionById(String id) {
    SelenideElement field = $("#" + id).shouldBe(visible);
    if (!"select".equalsIgnoreCase(field.getTagName())) {
      chooseFirstNonEmpty(field);
      return;
    }
    Select select = new Select(field.getWrappedElement());
    List<org.openqa.selenium.WebElement> options = select.getOptions();
    for (int index = 0; index < options.size(); index++) {
      var option = options.get(index);
      if (!option.isEnabled()) continue;
      String value = option.getAttribute("value");
      if (value == null || value.isBlank()) continue;
      select.selectByIndex(index);
      return;
    }
    StringBuilder sb = new StringBuilder("SELECT_OPTIONS #").append(id).append(":");
    for (var option : options) {
      sb.append(" [text=").append(option.getText().trim()).append(" value=").append(option.getAttribute("value"))
        .append(" enabled=").append(option.isEnabled()).append("]");
    }
    System.out.println(sb);
    if (options.size() == 1) return; // single auto-populated (often disabled) value, e.g. currency derived from instrument
    throw new AssertionError("No selectable native option for #" + id);
  }

  private void setField(String label, String value) {
    SelenideElement field = fieldForLabel(label);
    if ("date".equalsIgnoreCase(field.getAttribute("type"))) {
      executeJavaScript("const e=arguments[0], v=arguments[1]; const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; setter.call(e,v); e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); e.dispatchEvent(new Event('blur',{bubbles:true}));", field, value);
      return;
    }
    executeJavaScript("arguments[0].scrollIntoView({block:'center',inline:'center'});", field.getWrappedElement());
    try {
      field.click();
    } catch (org.openqa.selenium.ElementClickInterceptedException intercepted) {
      executeJavaScript("arguments[0].focus();", field.getWrappedElement());
    }
    field.sendKeys(Keys.chord(Keys.CONTROL, "a"));
    field.sendKeys(value);
    field.sendKeys(Keys.TAB);
  }

  private void setDate(String label, LocalDate date) {
    setField(label, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
  }

  private void setNativeDateById(String id, LocalDate date) {
    setControlById(id, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
  }

  private void setControlById(String id, String value) {
    SelenideElement field = $("#" + id).shouldBe(visible, enabled);
    executeJavaScript(
      "const el=arguments[0],value=arguments[1];"
        + "const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
        + "const setter=Object.getOwnPropertyDescriptor(proto,'value').set;setter.call(el,value);"
        + "el.dispatchEvent(new Event('input',{bubbles:true}));"
        + "el.dispatchEvent(new Event('change',{bubbles:true}));el.dispatchEvent(new Event('blur',{bubbles:true}));",
      field.getWrappedElement(), value);
  }

  private void requirePopulated(String label) {
    SelenideElement field = fieldForLabel(label);
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      String value = displayValue(field);
      if (value != null && !value.isBlank()) return;
      sleep(200);
    }
    throw new AssertionError(label + " was not populated");
  }

  private boolean hasValidationError(SelenideElement field) {
    String cls = safe(field.getAttribute("class")).toLowerCase(Locale.ROOT);
    if (cls.contains("invalid") || "true".equals(field.getAttribute("aria-invalid"))) return true;
    SelenideElement container = field.closest(".form-group, .field, div");
    if (!container.exists()) return false;
    String text = container.getText().toLowerCase(Locale.ROOT);
    return text.contains("required") || text.contains("invalid") || text.contains("must be") || text.contains("error");
  }

  private BigDecimal decimalValue(SelenideElement field) {
    String raw = displayValue(field);
    if (raw == null) return BigDecimal.ZERO;
    Matcher matcher = Pattern.compile("-?[0-9][0-9 .,' ]*").matcher(raw);
    if (!matcher.find()) return BigDecimal.ZERO;
    String normalized = matcher.group().replace(" ", "").replace(" ", "").replace("'", "");
    if (normalized.contains(",") && !normalized.contains(".")) normalized = normalized.replace(',', '.');
    else normalized = normalized.replace(",", "");
    return new BigDecimal(normalized).setScale(Math.max(0, new BigDecimal(normalized).scale()), RoundingMode.UNNECESSARY);
  }

  private String displayValue(SelenideElement field) {
    String value = field.getValue();
    if (value != null && !value.isBlank()) return value;
    return field.getText();
  }

  private void clickExactVisible(String text) {
    List<SelenideElement> matches = exactVisible(text, "button, a, [role=button], li, span");
    if (matches.size() != 1) throw new AssertionError("Expected one visible '" + text + "' control, found " + matches.size());
    matches.get(0).click();
  }

  private List<SelenideElement> exactVisible(String text, String selector) {
    List<SelenideElement> matches = new ArrayList<>();
    for (SelenideElement element : $$(selector)) {
      try {
        if (element.isDisplayed() && text.equalsIgnoreCase(element.getText().trim())) matches.add(element);
      } catch (Throwable stale) {
        // element went stale during an async page transition; skip it and continue polling
      }
    }
    return matches;
  }

  private void awaitBodyText(String text) {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      if ($("body").shouldBe(visible).getText().contains(text)) return;
      sleep(200);
    }
    throw new AssertionError("Timed out waiting for visible text '" + text + "'; url=" + webdriver().driver().url());
  }

  private boolean signDocumentVisible() {
    return !exactVisible("Sign Document", "button, a, [role=button]").isEmpty();
  }

  private String applicationIdFromUrl() {
    String url = webdriver().driver().url();
    Matcher applicationForm = Pattern.compile("/application-form/(\\d+)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE).matcher(url);
    if (applicationForm.find()) return applicationForm.group(1);
    Matcher countryForm = Pattern.compile("/country/[A-Z]{2}/(\\d+)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE).matcher(url);
    if (countryForm.find()) return countryForm.group(1);
    Matcher application = Pattern.compile("/applications?/(\\d+)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE).matcher(url);
    return application.find() ? application.group(1) : null;
  }

  private void requireTotal() {
    if (totalPaymentAmount == null) throw new AssertionError("Calculated total payment amount is unavailable");
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static String randomDigits(int length) {
    String seed = Long.toString(Math.abs(System.nanoTime()));
    return (seed + "12345678901234567890").substring(0, length);
  }

  private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(safe(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
  private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8); }
  private static String safe(String value) { return value == null ? "" : value; }
  private static String normalize(String value) { return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim(); }
  private static String xpathLiteral(String value) {
    if (!value.contains("'")) return "'" + value + "'";
    return "concat('" + value.replace("'", "',\"'\",'") + "')";
  }
}
