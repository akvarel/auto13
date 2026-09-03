package steps;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.SelenideElement;
import io.cucumber.java.en.Then;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.sleep;
import static com.codeborne.selenide.WebDriverRunner.url;

/** Concrete replacement for the generated `signees_list_visible` pseudo-oracle. */
public final class AdminSigneesRepairSteps {
  private static final String EXPECTED_SIGNER = "MARY ÄNN O’CONNEŽ-ŠUSLIK TESTNUMBER";
  private static final Pattern SIGNING_DATE = Pattern.compile("\\b\\d{2}/\\d{2}/\\d{4}\\b");

  @Then("the signees list is visible")
  public void adminSigneesListVisible() {
    long deadline = System.currentTimeMillis() + Configuration.timeout;
    while (System.currentTimeMillis() < deadline) {
      String body = $("body").shouldBe(visible).getText();
      boolean activeSignatures = CorporateActionsTabProbe.isActive("Signatures")
        && body.toLowerCase(Locale.ROOT).contains("signatures");
      boolean populatedSignerRow = false;
      for (SelenideElement row : $$("table tbody tr, [role=row], ul li").filterBy(visible)) {
        String rowText = row.getText();
        if (rowText.contains(EXPECTED_SIGNER) && SIGNING_DATE.matcher(rowText).find()) {
          populatedSignerRow = true;
          break;
        }
      }
      if (activeSignatures && populatedSignerRow) return;
      sleep(200);
    }
    throw new AssertionError("Admin signing flow did not expose a populated signees/signers surface; url=" + url());
  }
}
