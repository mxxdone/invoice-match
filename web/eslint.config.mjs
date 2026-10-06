import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import { plugin as shadcn } from "@shadcn/lint";
import { designHooks } from "./eslint-design-hooks.mjs";

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    files: ["**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}"],
    plugins: { shadcn },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "shadcn/no-raw-colors": "error",
      "shadcn/no-inline-styles": "error",
      "shadcn/require-static-classes": "error",
      "shadcn/no-unknown-classes": ["error", { allow: designHooks }],
    },
  },
  {
    files: ["src/components/ui/**/*.{ts,tsx}"],
    // Component implementations resolve their own cva variants at runtime.
    rules: { "shadcn/require-static-classes": "off" },
  },
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),
]);
