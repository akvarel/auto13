package steps;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.SelenideElement;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.sleep;
import static com.codeborne.selenide.WebDriverRunner.getWebDriver;
import static com.codeborne.selenide.WebDriverRunner.hasWebDriverStarted;
import static com.codeborne.selenide.WebDriverRunner.url;

/**
 * Prepares a disposable application in the lifecycle state required by admin
 * rejection. The application is created and signed through the real customer
 * UI, then the exact numeric identity is opened in admin. Cleanup is scoped to
 * that identity only.
 */
public final class SubmittedDisposableAdminSteps {
  private static final String TYPE = "Bonus Issue";
  private static final Pattern APPLICATION_ID =
    Pattern.compile("/corporate-actions/application-form/(\\d+)(?:[/?#].*)?$");

  private final DisposableScenarioPrerequisites customer;
  private final DisposableDividendSteps flow;
  private final AdminSteps admin;
  private String applicationId;

  public SubmittedDisposableAdminSteps(DisposableScenarioPrerequisites customer,
                                       DisposableDividendSteps flow,
                                       AdminSteps admin) {
    this.customer = customer;
    this.flow = flow;
    this.admin = admin;
  }

  @Given("a signed disposable customer Bonus Issue application is opened in the admin application")
  public void freshSubmittedCustomerApplicationOpenedInAdmin() throws Exception {
    customer.freshSavedDisposableApplication(TYPE);
    applicationId = applicationIdFromUrl(url());
    if (applicationId == null) {
      throw new AssertionError("Disposable Bonus Issue draft did not expose a numeric application ID; url=" + url());
    }

    flow.clickSignDocument();
    flow.signaturesTabVisible();
    flow.initiateSigningProcess();
    flow.signingFormVisible();
    flow.clickSignerSignButton();
    flow.signWithMobileId("60000666");
    flow.signatureIsValid();

    clearBrowserAuthenticationState();
    admin.i_am_authenticated_in_the_admin_application();
    admin.i_navigate_to_the_admin_string("/corporate-actions/application-form/" + applicationId);
    awaitExactAdminDetail();
  }

  @After(value = "@submitted_disposable_admin", order = 2000)
  public void cleanupSubmittedDisposable(Scenario scenario) {
    if (applicationId == null || !hasWebDriverStarted()) return;
    try {
      if (!isExactDetail()) {
        admin.i_am_authenticated_in_the_admin_application();
        admin.i_navigate_to_the_admin_string("/corporate-actions/application-form/" + applicationId);
        awaitExactAdminDetail();
      }
      deleteExactApplicationIfAvailable();
    } catch (Throwable failure) {
      scenario.log("Submitted disposable cleanup warning for application " + applicationId + ": "
        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    } finally {
      applicationId = null;
    }
  }

  private void awaitExactAdminDetail() {
    long deadline = System.currentTimeMillis() + Math.max(Configuration.timeout, RuntimeState.HANG_TIMEOUT_MS);
    String lastBody = "";
    while (System.currentTimeMillis() < deadline) {
      if (isExactDetail()) {
        lastBody = $("body").getText();
        if (lastBody != null && lastBody.contains(TYPE)) return;
      }
      sleep(100);
    }
    throw new AssertionError("Admin did not open exact submitted disposable application " + applicationId
      + "; url=" + url() + " body=" + trim(lastBody, 800));
  }

  private boolean isExactDetail() {
    String current = url();
    return current != null && applicationId != null
      && current.contains("/corporate-actions/application-form/" + applicationId)
      && !current.contains("/login");
  }

  private void deleteExactApplicationIfAvailable() {
    List<SelenideElement> deletes = exactVisibleControls("Delete");
    if (deletes.size() != 1) return;
    executeJavaScript("arguments[0].click();", deletes.get(0).getWrappedElement());
    sleep(250);

    List<SelenideElement> dialogs = new ArrayList<>();
    for (SelenideElement dialog : $$("[role=dialog],ngb-modal-window,.modal")) {
      if (dialog.isDisplayed()) dialogs.add(dialog);
    }
    if (dialogs.size() == 1) {
      List<SelenideElement> confirmations = new ArrayList<>();
      for (SelenideElement control : dialogs.get(0).$$("button,a,[role=button]")) {
        if (!control.isDisplayed() || !control.isEnabled()) continue;
        String label = clean(control.getText()).toLowerCase(Locale.ROOT);
        if (label.equals("delete") || label.equals("confirm") || label.contains("confirm deletion")) {
          confirmations.add(control);
        }
      }
      if (confirmations.size() == 1) {
        executeJavaScript("arguments[0].click();", confirmations.get(0).getWrappedElement());
      }
    }
  }

  private static List<SelenideElement> exactVisibleControls(String expected) {
    List<SelenideElement> result = new ArrayList<>();
    for (SelenideElement control : $$("button,a,[role=button],input[type=button],input[type=submit]")) {
      if (!control.isDisplayed() || !control.isEnabled()) continue;
      String label = clean(control.getText());
      if (label.isBlank()) label = clean(control.getAttribute("value"));
      if (label.isBlank()) label = clean(control.getAttribute("aria-label"));
      if (expected.equalsIgnoreCase(label)) result.add(control);
    }
    return result;
  }

  private static String applicationIdFromUrl(String current) {
    if (current == null) return null;
    Matcher matcher = APPLICATION_ID.matcher(current);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void clearBrowserAuthenticationState() {
    if (!hasWebDriverStarted()) return;
    try {
      executeJavaScript("try{localStorage.clear()}catch(e){} try{sessionStorage.clear()}catch(e){}");
    } catch (Throwable ignored) { }
    try { getWebDriver().manage().deleteAllCookies(); } catch (Throwable ignored) { }
  }

  private static String clean(String value) {
    return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
  }

  private static String trim(String value, int max) {
    String text = clean(value);
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
