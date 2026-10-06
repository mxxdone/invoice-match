import { clsx, type ClassValue } from "clsx"
import { extendTailwindMerge } from "tailwind-merge"

const merge = extendTailwindMerge({
  extend: { theme: { text: ["label", "detail"] } },
})

export function cn(...inputs: ClassValue[]) {
  return merge(clsx(inputs))
}
