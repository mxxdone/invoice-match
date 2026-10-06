import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const inputVariants = cva(
  "flex w-full rounded-sm border border-input text-foreground transition-colors placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:bg-disabled-surface disabled:text-disabled-foreground",
  {
    variants: {
      density: {
        default: "h-10 px-3 py-2 text-sm",
        compact: "h-9 px-2.5 py-1.5 text-sm",
      },
      variant: {
        default: "bg-background",
        filter: "bg-muted/30",
        embedded: "rounded-none border-0 bg-transparent",
        search: "rounded-none border-0 bg-transparent",
      },
      alignment: {
        left: "text-left",
        right: "text-right",
      },
    },
    compoundVariants: [
      { variant: "embedded", className: "h-full" },
      { variant: "search", className: "h-full px-0 py-0" },
    ],
    defaultVariants: { density: "default", variant: "default" },
  }
)

type InputProps = React.ComponentProps<"input"> & VariantProps<typeof inputVariants>

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, density, variant, alignment, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(inputVariants({ density, variant, alignment }), className)}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = "Input"

export { Input }
