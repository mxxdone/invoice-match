import { clsx, type ClassValue } from "clsx"
import { extendTailwindMerge } from "tailwind-merge"

const merge = extendTailwindMerge({
  extend: {
    theme: {
      text: ["label", "detail", "section", "title-sm", "display-sm", "title", "display"],
      leading: ["title", "detail", "hero", "body", "roomy", "air"],
      tracking: ["label", "heading", "metric", "display", "title", "hero", "brand", "number"],
    },
  },
})

export function cn(...inputs: ClassValue[]) {
  return merge(clsx(inputs))
}
