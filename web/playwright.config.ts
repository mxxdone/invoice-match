import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:3000",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "desktop-1280x720",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1280, height: 720 },
      },
    },
    {
      name: "tablet-768x1024",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 768, height: 1024 },
      },
    },
  ],
  webServer: {
    command: "npm run start",
    url: "http://127.0.0.1:3000/api/health",
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
});
