import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const groupStyles = cva("flex w-full items-center rounded-sm border border-input bg-background text-foreground", {
  variants: { density: {
    default: "h-10 gap-1.25",
    compact: "h-9 gap-2.25 px-2.5",
  } }, defaultVariants: { density: "default" },
})

type InputGroupProps = React.HTMLAttributes<HTMLDivElement> &
  VariantProps<typeof groupStyles> & { asChild?: boolean }

const InputGroup = React.forwardRef<HTMLDivElement, InputGroupProps>(
  ({ className, density, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "div"
    return <Comp ref={ref} data-slot="input-group" className={cn(groupStyles({ density }), className)} {...props} />
  }
)
InputGroup.displayName = "InputGroup"

export { InputGroup }
