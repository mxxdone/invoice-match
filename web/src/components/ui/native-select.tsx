import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const selectVariants = cva(
  "block w-full rounded-sm border border-input text-foreground disabled:cursor-not-allowed disabled:bg-disabled-surface disabled:text-disabled-foreground",
  {
    variants: {
      density: {
        default: "h-10 px-3 py-2 text-sm",
        compact: "h-9 px-2.5 py-1.5 text-sm",
        small: "h-8 px-2 py-1 text-label",
      },
      variant: {
        default: "bg-background",
        filter: "bg-muted/30",
      },
    },
    defaultVariants: { density: "default", variant: "default" },
  }
)

type NativeSelectProps = React.SelectHTMLAttributes<HTMLSelectElement> &
  VariantProps<typeof selectVariants>

const NativeSelect = React.forwardRef<HTMLSelectElement, NativeSelectProps>(
  ({ className, density, variant, ...props }, ref) => (
    <select
      ref={ref}
      data-slot="native-select"
      className={cn(selectVariants({ density, variant }), className)}
      {...props}
    />
  )
)
NativeSelect.displayName = "NativeSelect"

export { NativeSelect }
