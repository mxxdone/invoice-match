import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const tabVariants = cva(
  "inline-flex items-center gap-2 border-x-0 border-t-0 border-b-2 border-solid bg-transparent px-1.5 disabled:cursor-not-allowed disabled:opacity-50",
  {
    variants: {
      active: {
        true: "active border-b-olive text-ink",
        false: "border-b-transparent text-muted-foreground hover:text-ink",
      },
      density: {
        default: "min-h-13.25 text-label",
        detail: "min-h-14.5 text-sm",
      },
    },
    defaultVariants: { active: false, density: "default" },
  }
)

type UnderlineTabProps = React.ButtonHTMLAttributes<HTMLButtonElement> &
  VariantProps<typeof tabVariants>

const UnderlineTab = React.forwardRef<HTMLButtonElement, UnderlineTabProps>(
  ({ className, active = false, density, ...props }, ref) => (
    <button
      ref={ref}
      type="button"
      role="tab"
      aria-selected={Boolean(active)}
      data-slot="underline-tab"
      className={cn(tabVariants({ active, density }), className)}
      {...props}
    />
  )
)
UnderlineTab.displayName = "UnderlineTab"

export { UnderlineTab }
