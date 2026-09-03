package steps;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.SelenideElement;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebElement;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.sleep;
import static com.codeborne.selenide.WebDriverRunner.url;

/** Small current-detail navigation helper for lifecycle-specific admin tests. */
public final class CurrentAdminApplicationRepairSteps {
  @When("I open the current application's {string} tab")
  public void openCurrentTab(String name) {
    if (url() == null || !url().contains("/corporate-actions/application-form/")) {
      throw new AssertionError("Current admin application tab step requires an application detail; url=" + url());
    }
    if (CorporateActionsTabProbe.isActive(name)) return;
    WebElement clickable = CorporateActionsTabProbe.findClickable(name);
    if (clickable == null) throw new AssertionError("Current admin application exposes no " + name + " tab");
    CorporateActionsTabProbe.prepare(name);
    $(clickable).scrollIntoView("{block:'center',inline:'center'}").click();
    long deadline = System.currentTimeMillis() + Math.min(Configuration.timeout, 15000);
    while (System.currentTimeMillis() < deadline) {
      if (CorporateActionsTabProbe.isActive(name)) return;
      sleep(100);
    }
    throw new AssertionError("Current admin application tab did not become active: " + name);
  }
}
